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

            boolean isUnlimited = plan.equals("pro") || plan.equals("enterprise");
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