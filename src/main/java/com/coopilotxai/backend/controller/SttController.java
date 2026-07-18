package com.coopilotxai.backend.controller;

import com.coopilotxai.backend.security.IdentityResolverService;
import com.coopilotxai.backend.security.RequestIdentity;
import com.coopilotxai.backend.security.SimpleRateLimiter;
import com.coopilotxai.backend.service.FirestoreCreditsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * GET /api/v1/stt/key
 *
 * Issues a SHORT-LIVED Speechmatics real-time token to authenticated users, or —
 * with no Authorization header but an X-Device-Id header instead — to a
 * not-signed-in guest on the free trial.
 *
 * SECURITY: the master SPEECHMATICS_API_KEY never leaves this server. Callers
 * receive a temporary key minted via mp.speechmatics.com with a bounded TTL, so
 * a leaked/scraped response cannot drain the account indefinitely. Issuance is
 * additionally gated on the caller having credits remaining, and rate-limited
 * per IP and per identity so scripted X-Device-Id farming is throttled.
 *
 * Responses:
 *   200  { "key": "<temporary_rt_token>" }  — resolved identity, token minted
 *   401  { "error": "..." }                 — no valid token and no device ID
 *   402  { "error": "..." }                 — caller has no credits remaining
 *   429  { "error": "..." }                 — rate limited
 *   502  { "error": "..." }                 — Speechmatics rejected the mint
 *   503  { "error": "..." }                 — key not set on server (env var missing)
 */
@RestController
@RequestMapping("/api/v1/stt")
public class SttController {

    // One temporary token comfortably covers a full interview session; the
    // desktop engine re-fetches on every (re)start, so expiry self-heals.
    private static final int TOKEN_TTL_SECONDS = 3600;

    // An interview mints ~1 token per engine start. These ceilings are far above
    // real usage but stop token farming cold.
    private static final int PER_IDENTITY_PER_HOUR = 30;
    private static final int PER_IP_PER_MINUTE     = 10;

    @Autowired
    private IdentityResolverService identityResolver;

    @Autowired
    private FirestoreCreditsService creditsService;

    @Autowired
    private SimpleRateLimiter rateLimiter;

    @Value("${speechmatics.api.key:}")
    private String speechmaticsApiKey;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @GetMapping("/key")
    public ResponseEntity<?> getSttKey(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest request) {

        // 1. Verify Firebase token, or fall back to the free guest trial by device ID
        RequestIdentity identity = identityResolver.resolve(authHeader, deviceId);
        if (identity == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing token"));

        // 2. Rate limits — per IP first (cheapest), then per identity
        String ip = SimpleRateLimiter.clientIp(request);
        if (!rateLimiter.tryAcquire("stt-ip:" + ip, PER_IP_PER_MINUTE, 60_000L)
                || !rateLimiter.tryAcquire("stt-id:" + identity, PER_IDENTITY_PER_HOUR, 3_600_000L)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many token requests. Please wait a moment."));
        }

        // 3. Transcription is only issued to callers who can still afford to use it —
        //    a drained guest device or empty free account gets 402, not a token.
        boolean canAfford = identity.isGuest()
                ? creditsService.canAffordGuest(identity.deviceId())
                : creditsService.canAfford(identity.uid());
        if (!canAfford) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "No credits remaining"));
        }

        // 4. Check the master key is configured on the server
        if (speechmaticsApiKey == null || speechmaticsApiKey.isBlank()) {
            System.err.println("[STT] SPEECHMATICS_API_KEY env var is not set — returning 503");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Speech service not configured on server"));
        }

        // 5. Mint a short-lived temporary token — the master key stays server-side
        try {
            HttpRequest mintRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://mp.speechmatics.com/v1/api_keys?type=rt"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + speechmaticsApiKey)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"ttl\": " + TOKEN_TTL_SECONDS + "}"))
                    .build();

            HttpResponse<String> mintResponse =
                    httpClient.send(mintRequest, HttpResponse.BodyHandlers.ofString());

            if (mintResponse.statusCode() < 200 || mintResponse.statusCode() >= 300) {
                System.err.println("[STT] Speechmatics mint failed: HTTP "
                        + mintResponse.statusCode() + " " + mintResponse.body());
                String detail = mintResponse.statusCode() == 401
                        ? "Speechmatics API key is invalid or expired"
                        : mintResponse.statusCode() == 429
                            ? "Speechmatics quota exhausted"
                            : "Speech service temporarily unavailable";
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", detail));
            }

            JsonNode json = mapper.readTree(mintResponse.body());
            String tempKey = json.path("key_value").asText("");
            if (tempKey.isEmpty()) {
                System.err.println("[STT] Speechmatics mint response missing key_value");
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Speech service temporarily unavailable"));
            }

            System.out.println("[STT] Issued temporary SM token (ttl=" + TOKEN_TTL_SECONDS
                    + "s) to " + identity);
            return ResponseEntity.ok(Map.of("key", tempKey));

        } catch (Exception e) {
            System.err.println("[STT] Speechmatics mint error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Speech service temporarily unavailable"));
        }
    }
}
