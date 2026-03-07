package com.coopilotxai.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;

@Configuration
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
public class FirebaseConfig {

    @PostConstruct
    public void init() {
        if (!FirebaseApp.getApps().isEmpty()) return;

        try {
            String path = System.getenv("FIREBASE_SERVICE_ACCOUNT_PATH");
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("FIREBASE_SERVICE_ACCOUNT_PATH is missing");
            }

            GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(path));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();

            FirebaseApp.initializeApp(options);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Firebase Admin SDK", e);
        }
    }
}