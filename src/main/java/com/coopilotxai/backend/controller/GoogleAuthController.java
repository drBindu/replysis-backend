package com.coopilotxai.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String FIREBASE_IDP_URL =
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp" +
            "?key=AIzaSyAGGmuFpR0qkCHLI3q2cPv_o3cQlbIU8lE";

    private final ObjectMapper mapper     = new ObjectMapper();
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestBody Map<String, String> body) {
        String code         = body.get("code");
        String codeVerifier = body.get("codeVerifier");
        String redirectUri  = body.get("redirectUri");

        if (code == null || code.isBlank() || codeVerifier == null || codeVerifier.isBlank()
                || redirectUri == null || redirectUri.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing code, codeVerifier, or redirectUri"));
        }
        if (clientSecret == null || clientSecret.isBlank()
                || clientId == null || clientId.isBlank()) {
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
                System.err.println("[GoogleAuth] Google error: " + googleTokens.get("error")
                        + " — " + googleTokens.get("error_description"));
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
                    .uri(URI.create(FIREBASE_IDP_URL))
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
                System.err.println("[GoogleAuth] Firebase IDP error: " + firebaseBody.get("error"));
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

            System.out.println("[GoogleAuth] Sign-in OK — " + firebaseBody.get("email"));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.err.println("[GoogleAuth] Exchange failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Token exchange failed: " + e.getMessage()));
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
