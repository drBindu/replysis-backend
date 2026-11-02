package com.replysis.backend.service;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * India plans are paid once for 30 days (planSource "pass", passExpiresAt), never a subscription: monthly UPI and card
 * charges need an AutoPay mandate, and a customer's payment failed that way (2026-10-21). This job, every ten minutes:
 * emails a renew reminder about three days before the end (once per pass), and moves an ended pass back to free,
 * keeping any answers bought separately. The website sets creditsResetDate a day after the end, so a monthly refill can
 * never extend a pass before this runs.
 */
@Service
public class PassExpiryService {

    @Value("${firebase.enabled:false}") private boolean firebaseEnabled;
    @Autowired private AlertService alerts;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM").withZone(ZoneId.of("Asia/Kolkata"));

    static final int TRIAL_REMINDER_HOURS = 4;

    /** Plans that end at passExpiresAt: an India month paid once, or the 1 day trial every new account starts on. */
    static boolean timed(String planSource) {
        return "pass".equals(planSource) || "trial".equals(planSource);
    }

    /**
     * What is left when a trial or an India month ends. Normally only the answers bought separately. Practice bought
     * during it carries on: a trial's Practice numbers are already on the account, and a paid month hid them, so the
     * Practice month's numbers are written now (India months pair with the India Practice month).
     */
    static Map<String, Object> balanceAfterEnd(String source, long purchased, boolean practiceActive) {
        Map<String, Object> u = new HashMap<>();
        if (practiceActive && "trial".equals(source)) return u;
        if (practiceActive) {
            u.put("credits", purchased + FirestoreCreditsService.PRACTICE_CREDITS_INDIA);
            u.put("audioMinutesAllowance", FirestoreCreditsService.PRACTICE_AUDIO_MINUTES_INDIA);
            return u;
        }
        u.put("credits", purchased);
        u.put("audioMinutesAllowance", FieldValue.delete());
        return u;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void run() {
        if (!firebaseEnabled) return;
        Instant now = Instant.now();
        try {
            var db = FirestoreClient.getFirestore();

            // Ended: back to free.
            var ended = db.collection("users").whereLessThanOrEqualTo("passExpiresAt", now.toString())
                    .limit(100).get().get(15, TimeUnit.SECONDS);
            for (QueryDocumentSnapshot doc : ended.getDocuments()) {
                var ref = doc.getReference();
                // The end date leaves the account here, whatever happened to it. Ended accounts that kept it filled this
                // page, oldest first, so past 100 of them no newer trial would ever have been ended.
                if (!timed(doc.getString("planSource"))) {
                    ref.update("passExpiresAt", FieldValue.delete()).get(15, TimeUnit.SECONDS);
                    continue;
                }
                db.runTransaction(tx -> {
                    var snap = tx.get(ref).get();
                    String end = snap.getString("passExpiresAt");
                    String source = snap.getString("planSource");
                    if (!timed(source) || end == null || end.compareTo(Instant.now().toString()) > 0) return null;
                    Long purchased = snap.getLong("purchasedCredits");
                    boolean practice = FirestoreCreditsService.practiceActive(snap.getBoolean("mockPlan"),
                            snap.getString("mockPlanUntil"), Instant.now());
                    Map<String, Object> u = new HashMap<>();
                    u.put("plan", "free");
                    u.put("planSource", source + "-ended");
                    u.put("passExpiresAt", FieldValue.delete());
                    u.putAll(balanceAfterEnd(source, purchased == null ? 0 : Math.max(0, purchased), practice));
                    u.put("creditsResetDate", LocalDate.now(ZoneId.of("UTC")).withDayOfMonth(1).plusMonths(1)
                            .atStartOfDay(ZoneId.of("UTC")).toInstant().toString());
                    u.put("passEndedAt", FieldValue.serverTimestamp());
                    u.put("updatedAt", FieldValue.serverTimestamp());
                    tx.update(ref, u);
                    return null;
                }).get(15, TimeUnit.SECONDS);
                System.out.println("[PASS] " + doc.getString("planSource") + " ended, back to free: " + doc.getId());
            }

            // A Practice month paid once has ended. Its answers and listening stop; a paid plan on the account keeps its own.
            var practiceEnded = db.collection("users").whereLessThanOrEqualTo("mockPlanUntil", now.toString())
                    .limit(100).get().get(15, TimeUnit.SECONDS);
            for (QueryDocumentSnapshot doc : practiceEnded.getDocuments()) {
                var ref = doc.getReference();
                db.runTransaction(tx -> {
                    var snap = tx.get(ref).get();
                    String end = snap.getString("mockPlanUntil");
                    if (end == null || end.compareTo(Instant.now().toString()) > 0) return null;
                    Map<String, Object> u = new HashMap<>();
                    u.put("mockPlanUntil", FieldValue.delete());
                    u.put("updatedAt", FieldValue.serverTimestamp());
                    String plan = snap.getString("plan");
                    boolean onPlan = plan != null && !"free".equals(plan) && !"trial".equals(snap.getString("planSource"));
                    if (!onPlan && !Boolean.TRUE.equals(snap.getBoolean("mockPlan"))) {
                        Long purchased = snap.getLong("purchasedCredits");
                        u.put("credits", purchased == null ? 0 : Math.max(0, purchased));
                        u.put("audioMinutesAllowance", FieldValue.delete());
                    }
                    tx.update(ref, u);
                    return null;
                }).get(15, TimeUnit.SECONDS);
                System.out.println("[PASS] Practice month ended: " + doc.getId());
            }

            // A trial near its end: one reminder, with how to keep going. A trial is one day long, so this window must stay
            // well under a day or every new account would get the reminder as it signs up.
            var trialSoon = db.collection("users")
                    .whereGreaterThan("passExpiresAt", now.toString())
                    .whereLessThanOrEqualTo("passExpiresAt", now.plus(TRIAL_REMINDER_HOURS, ChronoUnit.HOURS).toString())
                    .limit(100).get().get(15, TimeUnit.SECONDS);
            for (QueryDocumentSnapshot doc : trialSoon.getDocuments()) {
                if (!"trial".equals(doc.getString("planSource"))) continue;
                String email = doc.getString("email");
                String end = doc.getString("passExpiresAt");
                if (email == null || end == null) continue;
                String name = doc.getString("displayName");
                String first = name == null || name.isBlank() ? "" : " " + name.trim().split("\s+")[0];
                alerts.toUserOnce("trial-reminder-" + doc.getId(), email,
                        "Your Replysis free trial ends soon",
                        "Hi" + first + ",\n\n"
                                + "Your free Replysis trial ends in a few hours.\n\n"
                                + "To keep live answers for your interviews, choose a plan here:\n"
                                + "https://replysis.com/pricing\n\n"
                                + "Good luck with your interviews,\nThe Replysis team\n");
            }

            // About three days left: one reminder per pass.
            var soon = db.collection("users")
                    .whereGreaterThan("passExpiresAt", now.plus(2, ChronoUnit.DAYS).toString())
                    .whereLessThanOrEqualTo("passExpiresAt", now.plus(3, ChronoUnit.DAYS).toString())
                    .limit(100).get().get(15, TimeUnit.SECONDS);
            for (QueryDocumentSnapshot doc : soon.getDocuments()) {
                if (!"pass".equals(doc.getString("planSource"))) continue;
                String email = doc.getString("email");
                String end = doc.getString("passExpiresAt");
                if (email == null || end == null) continue;
                String plan = "max".equals(doc.getString("plan")) ? "Max" : "Pro";
                String name = doc.getString("displayName");
                String first = name == null || name.isBlank() ? "" : " " + name.trim().split("\s+")[0];
                alerts.toUserOnce("pass-reminder-" + doc.getId() + "-" + end.substring(0, 10), email,
                        "Your Replysis " + plan + " ends on " + DAY.format(Instant.parse(end)),
                        "Hi" + first + ",\n\n"
                                + "Your Replysis " + plan + " month ends on " + DAY.format(Instant.parse(end)) + ".\n\n"
                                + "To keep going, pay for another month here (UPI or card, one payment, nothing automatic):\n"
                                + "https://replysis.com/pricing\n\n"
                                + "If you renew before it ends, the new month starts after the current one, so you lose nothing.\n\n"
                                + "Good luck with your interviews,\nThe Replysis team\n");
            }
        } catch (Exception e) {
            System.err.println("[PASS] run failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
