package com.coopilotxai.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.util.Base64;

@Configuration
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
public class FirebaseConfig {

    @PostConstruct
    public void init() {
        if (!FirebaseApp.getApps().isEmpty()) return;

        try {
            GoogleCredentials credentials = loadCredentials();
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Firebase Admin SDK", e);
        }
    }

    private GoogleCredentials loadCredentials() throws Exception {
        // 1. Try file path first (FIREBASE_SERVICE_ACCOUNT_PATH)
        String path = System.getenv("FIREBASE_SERVICE_ACCOUNT_PATH");
        if (path != null && !path.isBlank()) {
            try {
                return GoogleCredentials.fromStream(new FileInputStream(path));
            } catch (Exception ignored) {
                // File not accessible — fall through to env var
            }
        }

        // 2. Fall back to base64-encoded JSON in FIREBASE_PRIVATE_KEY
        String base64Key = System.getenv("FIREBASE_PRIVATE_KEY");
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(base64Key);
            return GoogleCredentials.fromStream(new ByteArrayInputStream(decoded));
        }

        throw new IllegalStateException(
            "No Firebase credentials found. Set FIREBASE_SERVICE_ACCOUNT_PATH or FIREBASE_PRIVATE_KEY.");
    }
}
