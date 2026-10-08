package com.replysis.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.replysis.backend.security.FirebaseAuthService;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.AlertService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * "Send me a test alert." Only the owner (the signed-in account whose email is ADMIN_EMAIL) can use it, and it only ever sends to the
 * address already configured for alerts, so it cannot be used to mail anyone else.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertsController {

    @Value("${ADMIN_EMAIL:}") private String adminEmail;

    @Autowired private FirebaseAuthService firebaseAuthService;
    @Autowired private AlertService alerts;
    @Autowired private SimpleRateLimiter rateLimiter;

    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                  HttpServletRequest request) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in first"));

        FirebaseToken token;
        try {
            token = firebaseAuthService.verify(authHeader.substring("Bearer ".length()).trim());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in first"));
        }
        if (adminEmail == null || adminEmail.isBlank() || token.getEmail() == null
                || !adminEmail.trim().equalsIgnoreCase(token.getEmail()) || !token.isEmailVerified())
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Owner only"));

        if (!rateLimiter.tryAcquire("alert-test:" + token.getUid(), 5, 60_000L))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").build();

        return ResponseEntity.ok(alerts.sendTest());
    }
}
