package com.replysis.backend.config;

import com.google.firebase.cloud.FirestoreClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Opens the Firestore connection as soon as the server is up, instead of on the first customer's question.
 *
 * Measured on a freshly started instance: the first credit charge took 2.6 seconds (the connection and its sign-in
 * token are made on first use), and every charge after it 0.17 seconds. Every deploy and every restart therefore
 * handed one person a slow first answer. One read of a document that does not exist pays that cost here, in the
 * background, before anybody is waiting.
 */
@Component
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
public class FirestoreWarmup {

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        Thread thread = new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                FirestoreClient.getFirestore().collection("_warmup").document("ping").get().get(20, TimeUnit.SECONDS);
                System.out.println("[WARMUP] Firestore connection ready in " + (System.currentTimeMillis() - started) + "ms");
            } catch (Exception e) {
                System.out.println("[WARMUP] Firestore warm-up did not finish: " + e.getClass().getSimpleName());
            }
        }, "firestore-warmup");
        thread.setDaemon(true);
        thread.start();
    }
}
