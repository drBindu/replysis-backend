package com.replysis.backend.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class FirestoreCreditsService {

    private static final int INTERVIEW_QUESTION_COST = 5;
    private static final int GUEST_FREE_CREDITS = 100;
    private static final String ANON_COLLECTION = "anon_devices";

    // Must match PLAN_MONTHLY_CREDITS in the website's app/api/stt/tokens/route.ts.
    // A plan missing here is normalised to "free", so an unlisted paid tier is
    // silently downgraded to the free allowance. "max" was missing, which meant a
    // Max subscriber was metered as a free user.
    private static final Map<String, Integer> PLAN_MONTHLY_CREDITS = Map.of(
            "free", 100,
            "pro", 2_000,
            "max", 5_000,
            // Retired plans, kept so an existing subscriber keeps their allowance.
            "lifetime", 5_000,
            "teams", 10_000
    );

    // ══════════════════════════════════════════════════════════════════════
    // LISTENING TIME
    //
    // Credits meter questions. Speechmatics bills by the hour of audio, and
    // nothing measured that, so the two costs that matter were disconnected:
    // somebody could hold the microphone open all afternoon, ask five
    // questions, spend twenty-five credits, and cost real money.
    //
    // Worse, the ordinary case was already thin. A Max plan is five thousand
    // credits, which is a thousand questions, which at the usual twenty
    // questions an hour is about fifty hours of audio: roughly the price of
    // the plan, before Stripe's cut. It survived only because most people
    // never finish what they bought.
    //
    // An hourly allowance sits beside the credits and is checked in the same
    // places. It is deliberately generous, because it exists to stop the
    // extreme case and not to be felt by anyone real: thirty hours is more
    // interviewing than almost anyone does in a month.
    // ══════════════════════════════════════════════════════════════════════
    private static final Map<String, Integer> PLAN_MONTHLY_AUDIO_MINUTES = Map.of(
            "free",       60,      //  1 hour
            "pro",       900,      // 15 hours
            "max",     1_800,      // 30 hours
            "lifetime",1_800,
            "teams",   6_000       // 100 hours, shared across the team
    );

    private static final int GUEST_FREE_AUDIO_MINUTES = 30;

    public static int monthlyAudioMinutes(String plan) {
        return PLAN_MONTHLY_AUDIO_MINUTES.getOrDefault(normalizePlan(plan),
               PLAN_MONTHLY_AUDIO_MINUTES.get("free"));
    }

    /**
     * Adds listening time to this month's total and says whether the caller is
     * still inside their allowance.
     *
     * Clients report minutes as they listen rather than at the end, so a
     * crashed app, a closed laptop or a dropped connection still leaves the
     * time accounted for. Reporting late and reporting nothing look identical
     * from here, and the second one is what a bill is made of.
     */
    public AudioUsage addListeningMinutes(String uid, int minutes) {
        if (minutes < 0) minutes = 0;
        // A single report cannot be worth more than a long interview. Guards
        // against a client with a broken clock donating someone else's month.
        if (minutes > 120) minutes = 120;

        final int add = minutes;
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection("users").document(uid);

            return db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) return new AudioUsage(0, monthlyAudioMinutes("free"), false, "free");

                String plan = normalizePlan(snap.getString("plan"));
                int allowance = monthlyAudioMinutes(plan);

                // Shares the credits reset date, so a user's month is one month
                // rather than two that drift apart and confuse everybody.
                long used = readLong(snap, "audioMinutesUsed");
                Instant resetAt = readResetDate(snap);
                if (resetAt != null && !Instant.now().isBefore(resetAt)) used = 0;

                used += add;
                tx.update(ref, "audioMinutesUsed", used);

                boolean unlimited = isUnlimitedPlan(plan);
                return new AudioUsage(safeInt(used), allowance, unlimited, plan);
            }).get();
        } catch (Exception e) {
            System.err.println("Firestore addListeningMinutes error: " + describe(e));
            // Never block someone mid-interview because Firestore hiccuped.
            return new AudioUsage(0, monthlyAudioMinutes("free"), false, "free");
        }
    }

    /**
     * The same meter for a device that has not signed in. Guests get half an
     * hour, which is enough to try the product properly and not enough to be
     * worth farming with fresh device ids.
     */
    public AudioUsage addGuestListeningMinutes(String deviceId, int minutes) {
        if (minutes < 0) minutes = 0;
        if (minutes > 120) minutes = 120;

        final int add = minutes;
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(ANON_COLLECTION).document(deviceId);

            return db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                long used = snap.exists() ? readLong(snap, "audioMinutesUsed") : 0;

                Instant resetAt = snap.exists() ? readResetDate(snap) : null;
                if (resetAt != null && !Instant.now().isBefore(resetAt)) used = 0;

                used += add;
                if (snap.exists()) tx.update(ref, "audioMinutesUsed", used);
                else tx.set(ref, Map.of("audioMinutesUsed", used,
                                        "creditsResetDate", nextResetDate()));

                return new AudioUsage(safeInt(used), GUEST_FREE_AUDIO_MINUTES, false, "guest");
            }).get();
        } catch (Exception e) {
            System.err.println("Firestore addGuestListeningMinutes error: " + describe(e));
            return new AudioUsage(0, GUEST_FREE_AUDIO_MINUTES, false, "guest");
        }
    }

    public boolean hasGuestAudioTimeLeft(String deviceId) {
        AudioUsage usage = addGuestListeningMinutes(deviceId, 0);
        return usage.usedMinutes < usage.allowanceMinutes;
    }

    /** True while the caller still has listening time left this month. */
    public boolean hasAudioTimeLeft(String uid) {
        AudioUsage usage = addListeningMinutes(uid, 0);
        return usage.isUnlimited || usage.usedMinutes < usage.allowanceMinutes;
    }

    public static class AudioUsage {
        public final int usedMinutes;
        public final int allowanceMinutes;
        public final boolean isUnlimited;
        public final String plan;

        public AudioUsage(int usedMinutes, int allowanceMinutes, boolean isUnlimited, String plan) {
            this.usedMinutes = usedMinutes;
            this.allowanceMinutes = allowanceMinutes;
            this.isUnlimited = isUnlimited;
            this.plan = plan;
        }

        public int remainingMinutes() {
            return isUnlimited ? Integer.MAX_VALUE : Math.max(0, allowanceMinutes - usedMinutes);
        }
    }

    public UserCredits getCredits(String uid) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection("users").document(uid);

            return db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) return new UserCredits(0, "free", false);

                String plan = normalizePlan(snap.getString("plan"));
                long credits = readCredits(snap);
                if (isUnlimitedPlan(plan)) {
                    return new UserCredits(safeInt(credits), plan, true);
                }
                Instant resetAt = readResetDate(snap);

                if (resetAt == null) {
                    tx.update(ref, "creditsResetDate", nextResetDate());
                } else if (!Instant.now().isBefore(resetAt)) {
                    // Top-up packs are bought outright and must survive the monthly
                    // refill. Resetting to the plan cap alone silently destroyed
                    // credits the customer had already paid for.
                    credits = monthlyCredits(plan) + readLong(snap, "purchasedCredits");
                    tx.update(ref,
                            "credits", credits,
                            "creditsUsed", 0,
                            "creditsResetDate", nextResetDate());
                }

                return new UserCredits(safeInt(credits), plan, false);
            }).get();
        } catch (Exception e) {
            System.err.println("Firestore getCredits error: " + describe(e));
            return new UserCredits(0, "free", false);
        }
    }

    public boolean canAfford(String uid) {
        return canAfford(uid, INTERVIEW_QUESTION_COST);
    }

    public boolean canAfford(String uid, int cost) {
        UserCredits current = getCredits(uid);
        return cost >= 0 && (current.isUnlimited || current.credits >= cost);
    }

    public boolean deductCredits(String uid) {
        return deductCredits(uid, INTERVIEW_QUESTION_COST);
    }

    public boolean deductCredits(String uid, int cost) {
        // Set inside the transaction, read after it. A transaction body can run
        // more than once on contention, so this records what the last attempt did.
        final java.util.concurrent.atomic.AtomicBoolean charged =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        if (cost < 0) return false;
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection("users").document(uid);

            boolean deducted = db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) return false;

                String plan = normalizePlan(snap.getString("plan"));
                if (isUnlimitedPlan(plan)) { charged.set(false); return true; }
                long credits = readCredits(snap);
                long creditsUsed = readLong(snap, "creditsUsed");
                Instant resetAt = readResetDate(snap);
                boolean resetNeeded = resetAt != null && !Instant.now().isBefore(resetAt);

                if (resetNeeded) {
                    // Same as getCredits: purchased top-ups survive the refill.
                    credits = monthlyCredits(plan) + readLong(snap, "purchasedCredits");
                    creditsUsed = 0;
                }

                if (credits < cost) {
                    if (resetAt == null || resetNeeded) {
                        tx.update(ref,
                                "credits", credits,
                                "creditsUsed", creditsUsed,
                                "creditsResetDate", nextResetDate());
                    }
                    return false;
                }

                tx.update(ref,
                        "credits", credits - cost,
                        "creditsUsed", creditsUsed + cost,
                        "creditsResetDate", resetAt == null || resetNeeded
                                ? nextResetDate() : resetAt.toString());
                return true;
            }).get();

            // "Allowed to proceed" and "credits were taken" are different facts,
            // and the log reported the first as though it were the second. An
            // unlimited plan is allowed and charged nothing, so every request on
            // one printed "Credits deducted: cost=5" against an account whose
            // balance never moved, which makes the log useless for the one
            // question worth asking it: was this user actually charged.
            if (deducted) {
                System.out.println(charged.get()
                        ? "Credits deducted: uid=" + uid + " cost=" + cost
                        : "Allowed with no charge (unlimited plan): uid=" + uid);
            }
            return deducted;
        } catch (Exception e) {
            System.err.println("Firestore deductCredits error: " + describe(e));
            return false;
        }
    }

    /**
     * A failure description that actually identifies the failure.
     *
     * Every catch here logged e.getMessage() alone, and a good number of
     * exceptions carry no message: an ExecutionException wrapping a cause, a
     * NullPointerException from a field that was absent. The first refund
     * failure this service ever recorded printed "Firestore refundCredits
     * error: null", which says a refund was lost and nothing about why.
     *
     * A refund failing means a user was charged for something they did not
     * get, so it is the last place to be economical with detail.
     */
    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 600; t = t.getCause()) {
            if (sb.length() > 0) sb.append("  <- ");
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            if (t.getCause() == t) break;
        }
        return sb.toString();
    }

    public void refundCredits(String uid) {
        refundCredits(uid, INTERVIEW_QUESTION_COST);
    }

    public void refundCredits(String uid, int cost) {
        if (cost <= 0) return;
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection("users").document(uid);
            db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) return false;

                String plan = normalizePlan(snap.getString("plan"));
                if (isUnlimitedPlan(plan)) return false;
                int cap = monthlyCredits(plan);
                long credits = readCredits(snap);
                long creditsUsed = readLong(snap, "creditsUsed");
                Instant resetAt = readResetDate(snap);
                boolean resetNeeded = resetAt != null && !Instant.now().isBefore(resetAt);

                if (resetNeeded) {
                    credits = cap;
                    creditsUsed = 0;
                } else {
                    credits = Math.min(cap, credits + cost);
                    creditsUsed = Math.max(0, creditsUsed - cost);
                }

                tx.update(ref,
                        "credits", credits,
                        "creditsUsed", creditsUsed,
                        "creditsResetDate", resetAt == null || resetNeeded
                                ? nextResetDate() : resetAt.toString());
                return true;
            }).get();
            System.out.println("Credits refunded: uid=" + uid + " amount=" + cost);
        } catch (Exception e) {
            System.err.println("Firestore refundCredits error: " + describe(e));
        }
    }

    public UserCredits getGuestCredits(String deviceId) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(ANON_COLLECTION).document(deviceId);

            return db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) {
                    tx.set(ref, newGuestDocument(GUEST_FREE_CREDITS));
                    return new UserCredits(GUEST_FREE_CREDITS, "guest", false);
                }

                long credits = readCredits(snap);
                Instant resetAt = readResetDate(snap);
                if (resetAt == null || !Instant.now().isBefore(resetAt)) {
                    credits = GUEST_FREE_CREDITS;
                    tx.update(ref,
                            "credits", credits,
                            "creditsUsed", 0,
                            "creditsResetDate", nextResetDate(),
                            "plan", "guest");
                }
                return new UserCredits(safeInt(credits), "guest", false);
            }).get();
        } catch (Exception e) {
            System.err.println("Firestore getGuestCredits error: " + describe(e));
            return new UserCredits(0, "guest", false);
        }
    }

    public boolean canAffordGuest(String deviceId) {
        return canAffordGuest(deviceId, INTERVIEW_QUESTION_COST);
    }

    public boolean canAffordGuest(String deviceId, int cost) {
        return cost >= 0 && getGuestCredits(deviceId).credits >= cost;
    }

    public boolean deductGuestCredits(String deviceId) {
        return deductGuestCredits(deviceId, INTERVIEW_QUESTION_COST);
    }

    public boolean deductGuestCredits(String deviceId, int cost) {
        if (cost < 0) return false;
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(ANON_COLLECTION).document(deviceId);

            boolean deducted = db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                long credits;
                long creditsUsed;
                Instant resetAt;

                if (!snap.exists()) {
                    credits = GUEST_FREE_CREDITS;
                    creditsUsed = 0;
                    resetAt = null;
                } else {
                    credits = readCredits(snap);
                    creditsUsed = readLong(snap, "creditsUsed");
                    resetAt = readResetDate(snap);
                    if (resetAt == null || !Instant.now().isBefore(resetAt)) {
                        credits = GUEST_FREE_CREDITS;
                        creditsUsed = 0;
                    }
                }

                boolean resetNeeded = resetAt != null && !Instant.now().isBefore(resetAt);
                if (credits < cost) {
                    if (!snap.exists()) {
                        tx.set(ref, newGuestDocument(credits));
                    } else if (resetAt == null || resetNeeded) {
                        tx.update(ref,
                                "credits", credits,
                                "creditsUsed", creditsUsed,
                                "creditsResetDate", nextResetDate(),
                                "plan", "guest");
                    }
                    return false;
                }

                Map<String, Object> updated = Map.of(
                        "credits", credits - cost,
                        "creditsUsed", creditsUsed + cost,
                        "creditsResetDate", resetAt == null || resetNeeded
                                ? nextResetDate() : resetAt.toString(),
                        "plan", "guest"
                );
                if (snap.exists()) tx.update(ref, updated);
                else tx.set(ref, updated);
                return true;
            }).get();

            if (deducted) {
                System.out.println("Guest credits deducted: device=" + deviceId + " cost=" + cost);
            }
            return deducted;
        } catch (Exception e) {
            System.err.println("Firestore deductGuestCredits error: " + describe(e));
            return false;
        }
    }

    public void refundGuestCredits(String deviceId) {
        refundGuestCredits(deviceId, INTERVIEW_QUESTION_COST);
    }

    public void refundGuestCredits(String deviceId, int cost) {
        if (cost <= 0) return;
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(ANON_COLLECTION).document(deviceId);
            db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) return false;

                long credits = readCredits(snap);
                long creditsUsed = readLong(snap, "creditsUsed");
                Instant resetAt = readResetDate(snap);
                boolean resetNeeded = resetAt != null && !Instant.now().isBefore(resetAt);

                if (resetAt == null || resetNeeded) {
                    credits = GUEST_FREE_CREDITS;
                    creditsUsed = 0;
                } else {
                    credits = Math.min(GUEST_FREE_CREDITS, credits + cost);
                    creditsUsed = Math.max(0, creditsUsed - cost);
                }

                tx.update(ref,
                        "credits", credits,
                        "creditsUsed", creditsUsed,
                        "creditsResetDate", resetAt == null || resetNeeded
                                ? nextResetDate() : resetAt.toString(),
                        "plan", "guest");
                return true;
            }).get();
            System.out.println("Guest credits refunded: device=" + deviceId + " amount=" + cost);
        } catch (Exception e) {
            System.err.println("Firestore refundGuestCredits error: " + describe(e));
        }
    }

    private static Map<String, Object> newGuestDocument(long credits) {
        return Map.of(
                "credits", credits,
                "creditsUsed", GUEST_FREE_CREDITS - credits,
                "creditsResetDate", nextResetDate(),
                "plan", "guest"
        );
    }

    private static String normalizePlan(String plan) {
        if (plan == null) return "free";
        String normalized = plan.trim().toLowerCase();
        return PLAN_MONTHLY_CREDITS.containsKey(normalized) ? normalized : "free";
    }

    private static int monthlyCredits(String plan) {
        return PLAN_MONTHLY_CREDITS.getOrDefault(normalizePlan(plan), 100);
    }

    /**
     * Whether a plan is exempt from metering. Nothing is, any more.
     *
     * Every paid tier used to be unlimited on the desktop path, decided before
     * there were customers and before the website committed to numbers. The
     * website has since sold Pro as 2,000 credits a month and Max as 5,000, and
     * enforces exactly that, while the desktop app handed the same subscriber an
     * unlimited allowance and showed an infinity symbol where the balance goes.
     *
     * Two prices for the same plan depending on which of your own apps someone
     * opens is not a generosity worth keeping. It makes the pricing page untrue,
     * it hides usage from the person paying for it, and it leaves no honest way
     * to tell a heavy user they have reached a limit that was never applied.
     *
     * The plan caps here already matched the website exactly. Only this bypass
     * disagreed.
     */
    private static boolean isUnlimitedPlan(String plan) {
        return false;
    }

    private static long readCredits(DocumentSnapshot snap) {
        return readLong(snap, "credits");
    }

    private static long readLong(DocumentSnapshot snap, String field) {
        Long value = snap.getLong(field);
        return value != null ? Math.max(0, value) : 0;
    }

    private static Instant readResetDate(DocumentSnapshot snap) {
        Object raw = snap.get("creditsResetDate");
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return Instant.parse(value);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String nextResetDate() {
        return ZonedDateTime.now(ZoneOffset.UTC)
                .plusMonths(1)
                .withDayOfMonth(1)
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant()
                .toString();
    }

    private static int safeInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    public static class UserCredits {
        public final int credits;
        public final String plan;
        public final boolean isUnlimited;

        public UserCredits(int credits, String plan, boolean isUnlimited) {
            this.credits = credits;
            this.plan = plan;
            this.isUnlimited = isUnlimited;
        }
    }
}
