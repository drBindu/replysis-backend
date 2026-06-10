package com.coopilotxai.backend.controller;

import com.coopilotxai.backend.security.AuthUser;
import com.coopilotxai.backend.security.FirebaseAuthService;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * GET /api/stt/key
 *
 * Returns the Speechmatics API key to authenticated users.
 * The key is read from the SPEECHMATICS_API_KEY environment variable
 * (already wired in application.properties as speechmatics.api.key).
 *
 * Responses:
 *   200  { "key": "<speechmatics_key>" }   — authenticated, key configured
 *   401  { "error": "..." }                — missing / invalid Firebase token
 *   503  { "error": "..." }                — key not set on server (env var missing)
 */
@RestController
@RequestMapping("/api/stt")
public class SttController {

    @Autowired
    private FirebaseAuthService firebaseAuthService;

    @Value("${speechmatics.api.key:}")
    private String speechmaticsApiKey;

    @GetMapping("/key")
    public ResponseEntity<?> getSttKey(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // 1. Verify Firebase token
        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing token"));

        // 2. Check the key is configured on the server
        if (speechmaticsApiKey == null || speechmaticsApiKey.isBlank()) {
            System.err.println("[STT] SPEECHMATICS_API_KEY env var is not set — returning 503");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Speech service not configured on server"));
        }

        System.out.println("[STT] Issued SM key to uid=" + user.uid());
        return ResponseEntity.ok(Map.of("key", speechmaticsApiKey));
    }

    // ── same helper used in InterviewController ──────────────────────────────
    private AuthUser verifyToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            String token = authHeader.substring("Bearer ".length()).trim();
            FirebaseToken decoded = firebaseAuthService.verify(token);
            return new AuthUser(decoded.getUid(), decoded.getEmail(), decoded.getName());
        } catch (Exception e) {
            System.err.println("[STT] Token verification failed: " + e.getMessage());
            return null;
        }
    }
}
