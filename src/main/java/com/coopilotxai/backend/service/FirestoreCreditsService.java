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
    // same physical Mac can't re-trigger it by clearing app data or reinstalling: the
    // device ID is the Mac's IOPlatformUUID (hardware-tied — see DeviceIdentity.swift),
    // not app-generated.
    private static final int GUEST_FREE_CREDITS = 100;
    private static final String ANON_COLLECTION = "anon_devices";

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
            boolean isUnlimited = plan.equals("pro") || plan.equals("lifetime") || plan.equals("teams");
            return new UserCredits(
                credits != null ? credits.intValue() : 0,
                plan,
                isUnlimited
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
    public boolean deductCredits(String uid) {
        try {
            UserCredits current = getCredits(uid);

            // Pro/Enterprise = unlimited, never deduct
            if (current.isUnlimited) return true;

            if (current.credits < INTERVIEW_QUESTION_COST) return false;

            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection("users").document(uid);

            // Atomic decrement
            ref.update("credits", FieldValue.increment(-INTERVIEW_QUESTION_COST)).get();

            System.out.println("Credits deducted: uid=" + uid +
                " cost=" + INTERVIEW_QUESTION_COST +
                " remaining=" + (current.credits - INTERVIEW_QUESTION_COST));

            return true;
        } catch (Exception e) {
            System.err.println("Firestore deductCredits error: " + e.getMessage());
            return false;
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

    public boolean deductGuestCredits(String deviceId) {
        try {
            UserCredits current = getGuestCredits(deviceId);
            if (current.credits < INTERVIEW_QUESTION_COST) return false;

            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(ANON_COLLECTION).document(deviceId);
            ref.update("credits", FieldValue.increment(-INTERVIEW_QUESTION_COST)).get();

            System.out.println("Guest credits deducted: device=" + deviceId +
                " cost=" + INTERVIEW_QUESTION_COST +
                " remaining=" + (current.credits - INTERVIEW_QUESTION_COST));
            return true;
        } catch (Exception e) {
            System.err.println("Firestore deductGuestCredits error: " + e.getMessage());
            return false;
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