package com.replysis.backend.service;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Emails the owner when someone signs up (owner 2026-10-21: "email alerts who signs up with their email"). Looks once a minute
 * for accounts created in the last ten minutes; AlertService.notice sends each one once, whichever instance sees it first.
 */
@Service
public class SignupAlertService {

    @Value("${firebase.enabled:false}") private boolean firebaseEnabled;
    @Autowired private AlertService alerts;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.of("America/Chicago"));

    /** Plain words, no dashes or dots as decoration (the owner reads those as machine written). */
    static String welcome(String firstName) {
        return "Hi" + firstName + ",\n\n"
                + "Thanks for joining Replysis.\n\n"
                + "Here is how to get your first answer in two minutes:\n\n"
                + "1. Download the app from https://replysis.com (Windows or Mac)\n"
                + "2. Open it, sign in, and press Start\n"
                + "3. Ask a practice question out loud. The answer starts in under a second.\n\n"
                + "Your free 3 day Pro trial includes 50 answers. No card needed.\n\n"
                + "Invite a friend and you both get 5 more free answers. Your invite link is in the app menu, "
                + "or at https://replysis.com/account\n\n"
                + "Good luck with your interviews,\n"
                + "The Replysis team\n";
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void look() {
        if (!firebaseEnabled) return;
        try {
            Timestamp since = Timestamp.of(new Date(System.currentTimeMillis() - 10 * 60_000L));
            var found = FirestoreClient.getFirestore().collection("users")
                    .whereGreaterThan("createdAt", since).limit(50).get().get(10, TimeUnit.SECONDS);
            for (QueryDocumentSnapshot doc : found.getDocuments()) {
                String email = doc.getString("email");
                if (email == null || email.isBlank()) continue;
                String name = doc.getString("displayName");
                Timestamp created = doc.getTimestamp("createdAt");
                String when = created == null ? "just now" : WHEN.format(created.toDate().toInstant());
                alerts.notice("signup-" + doc.getId(),
                        "New Replysis signup: " + email,
                        "Someone just signed up.\n\nEmail: " + email
                                + (name == null || name.isBlank() ? "" : "\nName: " + name)
                                + "\nSigned up: " + when + " (US Central)"
                                + "\n\nSee what they do: https://replysis.com/admin");
                String first = name == null || name.isBlank() ? "" : " " + name.trim().split("\\s+")[0];
                alerts.toUserOnce("welcome-" + doc.getId(), email, "Welcome to Replysis", welcome(first));
            }
        } catch (Exception e) {
            System.err.println("[SIGNUP_ALERT] look failed: " + e.getClass().getSimpleName());
        }
    }
}
