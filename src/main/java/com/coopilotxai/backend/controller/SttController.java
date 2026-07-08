package com.coopilotxai.backend.controller;

import com.coopilotxai.backend.security.IdentityResolverService;
import com.coopilotxai.backend.security.RequestIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * GET /api/stt/key
 *
 * Returns the Speechmatics API key to authenticated users, or — with no
 * Authorization header but an X-Device-Id header instead — to a not-signed-in
 * guest using their free trial (transcription needs this key regardless of
 * whether the caller has an account, so guests must be able to get one too).
 * The key is read from the SPEECHMATICS_API_KEY environment variable
 * (already wired in application.properties as speechmatics.api.key).
 *
 * Responses:
 *   200  { "key": "<speechmatics_key>" }   — resolved identity, key configured
 *   401  { "error": "..." }                — no valid token and no device ID
 *   503  { "error": "..." }                — key not set on server (env var missing)
 */
@RestController
@RequestMapping("/api/v1/stt")
public class SttController {

    @Autowired
    private IdentityResolverService identityResolver;

    @Value("${speechmatics.api.key:}")
    private String speechmaticsApiKey;

    @GetMapping("/key")
    public ResponseEntity<?> getSttKey(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {

        // 1. Verify Firebase token, or fall back to the free guest trial by device ID
        RequestIdentity identity = identityResolver.resolve(authHeader, deviceId);
        if (identity == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing token"));

        // 2. Check the key is configured on the server
        if (speechmaticsApiKey == null || speechmaticsApiKey.isBlank()) {
            System.err.println("[STT] SPEECHMATICS_API_KEY env var is not set — returning 503");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Speech service not configured on server"));
        }

        System.out.println("[STT] Issued SM key to " + identity);
        return ResponseEntity.ok(Map.of("key", speechmaticsApiKey));
    }
}
