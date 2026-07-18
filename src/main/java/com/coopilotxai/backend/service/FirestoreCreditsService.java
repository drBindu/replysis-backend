package com.coopilotxai.backend.service;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FieldValue;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.stereotype.Service;

@Service
public class FirestoreCreditsService {

    private static final int INTERVIEW_QUESTION_COST = 5;

    // Free trial for people who haven't signed in yet — 100 credits (= 20 questions
    // at 5 each). Granted ONCE per device and never reset: when it runs out the app
    // prompts the user to sign in and buy more (buying requires an account). An
    // occasional-use paid product converts best when the trial is a single experience,
    // not a recurring free tier that removes any reason to pay. Tracked per-device so the
    // same physical machine can't re-trigger it by clearing app data or reinstalling:
    // the device ID is hardware-tied (IOPlatformUUID on Mac, MachineGuid on Windows),
    // not app-generated.
    private static final int GUEST_FREE_CREDITS = 100;
    private static final String ANON_COLLECTION = "anon_devices";

    private static boolean isUnlimitedPlan(String plan) {
        return "pro".equals(plan) || "lifetime".equals(plan) || "teams".equals(plan);
    }

    // ── Get user's current credits and plan ──────────────────────────────────
    public UserCredits getCredits(String uid) {
        try {
            Firestore db  = FirestoreClient.getFirestore();
            DocumentSnapshot snap = db.collection("users").document(uid).get().get();

            if (!snap.exists()) {
                return new UserCredits(0, "free", false);
            }

            Long   credits    = snap.getLong("credits");
            String plan       = snap.getString("plan");
            if (plan == null) plan = "free";

            // All paid plans are unlimited on the desktop app. The web enforces the
            // real monthly caps + lazy reset; the backend has no reset, so charging
            // paid users here would let a desktop-only Lifetime/Teams user hit 0 and
            // get stuck. Only "free" is metered on this path.
            return new UserCredits(
                credits != null ? credits.intValue() : 0,
                plan,
                isUnlimitedPlan(plan)
            );
        } catch (Exception e) {
            System.err.println("Firestore getCredits error: " + e.getMessage());
            return new UserCredits(0, "free", false);
        }
    }

    // ── Check if user can afford the action ─────────────────────────────────
    public boolean canAfford(String uid) {
        UserCredits c = getCredits(uid);
        if (c.isUnlimited) return true;
        return c.credits >= INTERVIEW_QUESTION_COST;
    }

    // ── Deduct credits atomically ────────────────────────────────────────────
    // Runs the read + check + decrement inside a single Firestore transaction so
    // N parallel requests can never all pass the balance check and drive the
    // account negative (the old read-then-increment pattern allowed exactly that).
    public boolean deductCredits(String uid) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection("users").document(uid);

            boolean deducted = db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) return false;

                String plan = snap.getString("plan");
                if (plan == null) plan = "free";
                if (isUnlimitedPlan(plan)) return true;   // never deduct paid plans here

                Long creditsVal = snap.getLong("credits");
                long credits = creditsVal != null ? creditsVal : 0;
                if (credits < INTERVIEW_QUESTION_COST) return false;

                tx.update(ref, "credits", credits - INTERVIEW_QUESTION_COST);
                return true;
            }).get();

            if (deducted) {
                System.out.println("Credits deducted: uid=" + uid + " cost=" + INTERVIEW_QUESTION_COST);
            }
            return deducted;
        } catch (Exception e) {
            System.err.println("Firestore deductCredits error: " + e.getMessage());
            return false;
        }
    }

    // ── Refund after a failed AI call ────────────────────────────────────────
    // Credits are deducted BEFORE the AI provider is called (so an answer can
    // never be served unpaid); when every provider fails, the charge is returned.
    // Unlimited plans were never deducted, so there is nothing to refund.
    public void refundCredits(String uid) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection("users").document(uid);
            DocumentSnapshot snap = ref.get().get();
            if (!snap.exists()) return;

            String plan = snap.getString("plan");
            if (plan == null) plan = "free";
            if (isUnlimitedPlan(plan)) return;

            ref.update("credits", FieldValue.increment(INTERVIEW_QUESTION_COST)).get();
            System.out.println("Credits refunded: uid=" + uid + " amount=" + INTERVIEW_QUESTION_COST);
        } catch (Exception e) {
            System.err.println("Firestore refundCredits error: " + e.getMessage());
        }
    }

    // ── Guest (no sign-in) credits, tracked per hardware device ID ───────────

    public UserCredits getGuestCredits(String deviceId) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(ANON_COLLECTION).document(deviceId);
            DocumentSnapshot snap = ref.get().get();

            if (!snap.exists()) {
                // First time this device has ever been seen — grant the full free trial.
                ref.set(java.util.Map.of("credits", GUEST_FREE_CREDITS)).get();
                return new UserCredits(GUEST_FREE_CREDITS, "guest", false);
            }
            Long credits = snap.getLong("credits");
            return new UserCredits(credits != null ? credits.intValue() : 0, "guest", false);
        } catch (Exception e) {
            System.err.println("Firestore getGuestCredits error: " + e.getMessage());
            return new UserCredits(0, "guest", false);
        }
    }

    public boolean canAffordGuest(String deviceId) {
        return getGuestCredits(deviceId).credits >= INTERVIEW_QUESTION_COST;
    }

    // Transactional for the same reason as deductCredits: parallel guest requests
    // must not be able to spend the same credits twice.
    public boolean deductGuestCredits(String deviceId) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(ANON_COLLECTION).document(deviceId);

            boolean deducted = db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                long credits;
                if (!snap.exists()) {
                    // First contact happens via getGuestCredits normally, but handle
                    // the direct-deduct path too: grant the trial, then charge it.
                    credits = GUEST_FREE_CREDITS;
                    if (credits < INTERVIEW_QUESTION_COST) return false;
                    tx.set(ref, java.util.Map.of("credits", credits - INTERVIEW_QUESTION_COST));
                    return true;
                }
                Long creditsVal = snap.getLong("credits");
                credits = creditsVal != null ? creditsVal : 0;
                if (credits < INTERVIEW_QUESTION_COST) return false;

                tx.update(ref, "credits", credits - INTERVIEW_QUESTION_COST);
                return true;
            }).get();

            if (deducted) {
                System.out.println("Guest credits deducted: device=" + deviceId + " cost=" + INTERVIEW_QUESTION_COST);
            }
            return deducted;
        } catch (Exception e) {
            System.err.println("Firestore deductGuestCredits error: " + e.getMessage());
            return false;
        }
    }

    public void refundGuestCredits(String deviceId) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(ANON_COLLECTION).document(deviceId);
            DocumentSnapshot snap = ref.get().get();
            if (!snap.exists()) return;

            ref.update("credits", FieldValue.increment(INTERVIEW_QUESTION_COST)).get();
            System.out.println("Guest credits refunded: device=" + deviceId + " amount=" + INTERVIEW_QUESTION_COST);
        } catch (Exception e) {
            System.err.println("Firestore refundGuestCredits error: " + e.getMessage());
        }
    }

    // ── DTO ──────────────────────────────────────────────────────────────────
    public static class UserCredits {
        public final int     credits;
        public final String  plan;
        public final boolean isUnlimited;

        public UserCredits(int credits, String plan, boolean isUnlimited) {
            this.credits     = credits;
            this.plan        = plan;
            this.isUnlimited = isUnlimited;
        }
    }
}
