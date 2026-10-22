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
                if (!"pass".equals(doc.getString("planSource"))) continue;
                var ref = doc.getReference();
                db.runTransaction(tx -> {
                    var snap = tx.get(ref).get();
                    String end = snap.getString("passExpiresAt");
                    if (!"pass".equals(snap.getString("planSource")) || end == null || end.compareTo(Instant.now().toString()) > 0) return null;
                    Long purchased = snap.getLong("purchasedCredits");
                    Map<String, Object> u = new HashMap<>();
                    u.put("plan", "free");
                    u.put("planSource", "pass-ended");
                    u.put("credits", purchased == null ? 0 : Math.max(0, purchased));
                    u.put("audioMinutesAllowance", FieldValue.delete());
                    u.put("creditsResetDate", LocalDate.now(ZoneId.of("UTC")).withDayOfMonth(1).plusMonths(1)
                            .atStartOfDay(ZoneId.of("UTC")).toInstant().toString());
                    u.put("passEndedAt", FieldValue.serverTimestamp());
                    u.put("updatedAt", FieldValue.serverTimestamp());
                    tx.update(ref, u);
                    return null;
                }).get(15, TimeUnit.SECONDS);
                System.out.println("[PASS] ended, back to free: " + doc.getId());
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
