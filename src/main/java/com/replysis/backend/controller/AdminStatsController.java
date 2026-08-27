package com.replysis.backend.controller;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who is actually using this, answered from our own records.
 *
 * The Store's analytics run one to three days behind and count downloads. A
 * download is close to meaningless on its own: it says somebody clicked
 * Install, not that the app worked for them, and it cannot distinguish a person
 * who asked forty questions from one who opened it once and never came back.
 *
 * These numbers come from the credit ledger, which is written the moment an
 * answer is generated, so they are current and they are about use rather than
 * intent. The one worth watching is devicesThatAskedSomething against
 * devicesSeen: a large gap means people are installing and bouncing, and for a
 * fortnight there was a good reason to bounce - on a machine with no default
 * microphone the app said "connecting" for ever and explained nothing.
 *
 * Reads every document in both collections, which is fine at this size and
 * would not be at a hundred thousand. Capped so it degrades into an
 * underestimate rather than a bill.
 */
@RestController
public class AdminStatsController {

    /** Above this, stop counting and say the number is partial. */
    private static final int SCAN_LIMIT = 20_000;

    private static final int CREDITS_PER_QUESTION = 5;

    @GetMapping("/admin-api/stats")
    public ResponseEntity<?> stats() {
        try {
            Firestore db = FirestoreClient.getFirestore();

            long devicesSeen = 0, devicesAsked = 0, guestCreditsUsed = 0, guestMinutes = 0;
            boolean truncated = false;

            for (QueryDocumentSnapshot d :
                    db.collection("anon_devices").limit(SCAN_LIMIT).get().get().getDocuments()) {
                devicesSeen++;
                long used = num(d.get("creditsUsed"));
                if (used > 0) devicesAsked++;
                guestCreditsUsed += used;
                guestMinutes += num(d.get("audioMinutesUsed"));
            }
            if (devicesSeen >= SCAN_LIMIT) truncated = true;

            long users = 0, usersAsked = 0, userCreditsUsed = 0, userMinutes = 0;
            for (QueryDocumentSnapshot d :
                    db.collection("users").limit(SCAN_LIMIT).get().get().getDocuments()) {
                users++;
                long used = num(d.get("creditsUsed"));
                if (used > 0) usersAsked++;
                userCreditsUsed += used;
                userMinutes += num(d.get("audioMinutesUsed"));
            }
            if (users >= SCAN_LIMIT) truncated = true;

            var out = new LinkedHashMap<String, Object>();
            out.put("devicesSeen", devicesSeen);
            out.put("devicesThatAskedSomething", devicesAsked);
            // The number the Store cannot give you. Everyone who installed,
            // opened it, and never got an answer out of it.
            out.put("devicesThatNeverAsked", devicesSeen - devicesAsked);
            out.put("signedInUsers", users);
            out.put("signedInUsersThatAskedSomething", usersAsked);
            out.put("questionsAnswered",
                    (guestCreditsUsed + userCreditsUsed) / CREDITS_PER_QUESTION);
            out.put("listeningMinutes", guestMinutes + userMinutes);
            if (truncated) out.put("truncated", "counts are partial: scan limit reached");

            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Firestore hands back Long, Double or nothing depending on how it was written. */
    private static long num(Object value) {
        return (value instanceof Number n) ? n.longValue() : 0L;
    }
}
