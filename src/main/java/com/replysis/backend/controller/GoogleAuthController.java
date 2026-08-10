package com.replysis.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.replysis.backend.security.SimpleRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * POST /api/v1/auth/google/exchange
 *
 * Step 1: Exchange the PKCE authorization code with Google → access_token.
 * Step 2: Sign into Firebase using signInWithIdp with the Google access_token.
 * Step 3: Return Firebase credentials (idToken, refreshToken, email, displayName, localId)
 *         so the desktop client is fully authenticated without a second round-trip.
 *
 * The client secret never leaves this server.
 *
 * Request:  { "code": "...", "codeVerifier": "...", "redirectUri": "http://127.0.0.1:PORT/" }
 * Response: 200 { "idToken": "firebase-id-token", "refreshToken": "...",
 *                 "email": "...", "displayName": "...", "localId": "..." }
 *           400 missing fields  |  502 Google/Firebase error  |  503 server not configured
 */
@RestController
@RequestMapping("/api/v1/auth/google")
public class GoogleAuthController {

    @Value("${google.oauth.client-id:}")
    private String clientId;

    @Value("${google.oauth.client-secret:}")
    private String clientSecret;

    @Value("${firebase.web-api-key:}")
    private String firebaseWebApiKey;

    @org.springframework.beans.factory.annotation.Autowired
    private SimpleRateLimiter rateLimiter;

    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String FIREBASE_IDP_URL =
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=";

    private final ObjectMapper mapper     = new ObjectMapper();
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!rateLimiter.tryAcquire("google-auth:" + rateLimiter.clientIp(request), 10, 60_000L)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many sign-in attempts. Please wait a moment."));
        }
        String code         = body.get("code");
        String codeVerifier = body.get("codeVerifier");
        String redirectUri  = body.get("redirectUri");

        if (code == null || code.isBlank() || code.length() > 2_048
                || codeVerifier == null || codeVerifier.length() < 43 || codeVerifier.length() > 128
                || redirectUri == null || !isAllowedRedirectUri(redirectUri)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid sign-in callback parameters"));
        }
        if (clientSecret == null || clientSecret.isBlank()
                || clientId == null || clientId.isBlank()
                || firebaseWebApiKey == null || firebaseWebApiKey.isBlank()) {
            System.err.println("[GoogleAuth] GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET not set");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Google sign-in not configured on server"));
        }

        try {
            // ── 1. Exchange auth code → Google tokens ──────────────────────
            String formBody = "code="           + enc(code)
                    + "&client_id="             + enc(clientId)
                    + "&client_secret="         + enc(clientSecret)
                    + "&redirect_uri="          + enc(redirectUri)
                    + "&code_verifier="         + enc(codeVerifier)
                    + "&grant_type=authorization_code";

            HttpRequest googleReq = HttpRequest.newBuilder()
                    .uri(URI.create(GOOGLE_TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> googleRes = httpClient.send(googleReq,
                    HttpResponse.BodyHandlers.ofString());

            @SuppressWarnings("unchecked")
            Map<String, Object> googleTokens = mapper.readValue(googleRes.body(), Map.class);

            if (googleTokens.containsKey("error")) {
                // Code only. error_description is free text from the provider and
                // has been observed to echo request parameters.
                System.err.println("[GoogleAuth] Google rejected the exchange: code="
                        + googleTokens.get("error"));
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Google rejected the sign-in request"));
            }

            String accessToken = (String) googleTokens.get("access_token");
            if (accessToken == null || accessToken.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Google returned no access_token"));
            }

            // ── 2. Sign into Firebase via Google access_token ──────────────
            Map<String, Object> idpPayload = new HashMap<>();
            idpPayload.put("postBody",
                    "access_token=" + enc(accessToken) + "&providerId=google.com");
            idpPayload.put("requestUri",          "http://localhost");
            idpPayload.put("returnIdpCredential", true);
            idpPayload.put("returnSecureToken",   true);

            HttpRequest firebaseReq = HttpRequest.newBuilder()
                    .uri(URI.create(FIREBASE_IDP_URL + enc(firebaseWebApiKey)))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(idpPayload)))
                    .build();

            HttpResponse<String> firebaseRes = httpClient.send(firebaseReq,
                    HttpResponse.BodyHandlers.ofString());

            @SuppressWarnings("unchecked")
            Map<String, Object> firebaseBody = mapper.readValue(firebaseRes.body(), Map.class);

            if (firebaseRes.statusCode() < 200 || firebaseRes.statusCode() >= 300
                    || firebaseBody.containsKey("error")) {
                // The error object nests a message and the original request; log
                // the HTTP status alone.
                System.err.println("[GoogleAuth] Identity provider sign-in failed: status="
                        + firebaseRes.statusCode());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Firebase sign-in failed"));
            }

            // ── 3. Return Firebase credentials ─────────────────────────────
            Map<String, Object> result = new HashMap<>();
            result.put("idToken",      firebaseBody.get("idToken"));
            result.put("refreshToken", firebaseBody.get("refreshToken"));
            result.put("email",        firebaseBody.get("email"));
            result.put("displayName",  firebaseBody.getOrDefault("displayName", ""));
            result.put("localId",      firebaseBody.get("localId"));

            // Confirm the exchange succeeded without recording who signed in —
            // the email address is personal information and stays out of logs.
            System.out.println("[GoogleAuth] Sign-in exchange completed");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.err.println("[GoogleAuth] Exchange failed: " + e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Token exchange failed. Please try again."));
        }
    }

    private static boolean isAllowedRedirectUri(String raw) {
        try {
            URI uri = URI.create(raw);
            return "http".equalsIgnoreCase(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() >= 1024
                    && uri.getPort() <= 65_535
                    && "/".equals(uri.getPath())
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
