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
import java.util.Map;

/**
 * POST /api/v1/auth/google/exchange
 *
 * Completes the Google OAuth authorization-code exchange server-side, so the
 * client secret never has to ship inside the Mac app. The Mac app still does
 * everything else exactly as before (PKCE code_verifier generation, opening
 * the browser, running the local loopback listener to catch the redirect) —
 * only this one step, which needs the secret, moves here.
 *
 * No auth header is required or expected: the caller doesn't have a Firebase
 * token yet at this point in the sign-in flow (that's the whole point). The
 * security boundary is the same one Google itself relies on — possession of
 * a genuine authorization `code` (only obtainable by a user who just completed
 * Google's real consent screen) plus the matching PKCE `codeVerifier`.
 *
 * Request body:  { "code": "...", "codeVerifier": "...", "redirectUri": "http://127.0.0.1:PORT/" }
 * Response:      200 { "idToken": "...", "accessToken": "..." }
 *                400 { "error": "..." }  — missing fields
 *                502 { "error": "..." }  — Google rejected the exchange
 *                503 { "error": "..." }  — server not configured with the secret
 */
@RestController
@RequestMapping("/api/v1/auth/google")
public class GoogleAuthController {

    @Value("${google.oauth.client-id:}")
    private String clientId;

    @Value("${google.oauth.client-secret:}")
    private String clientSecret;

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        String codeVerifier = body.get("codeVerifier");
        String redirectUri = body.get("redirectUri");

        if (code == null || code.isBlank() || codeVerifier == null || codeVerifier.isBlank()
                || redirectUri == null || redirectUri.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing code, codeVerifier, or redirectUri"));
        }
        if (clientSecret == null || clientSecret.isBlank() || clientId == null || clientId.isBlank()) {
            System.err.println("[GoogleAuth] GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET not set on server");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Google sign-in not configured on server"));
        }

        try {
            String bodyStr = "code=" + enc(code)
                    + "&client_id=" + enc(clientId)
                    + "&client_secret=" + enc(clientSecret)
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&code_verifier=" + enc(codeVerifier)
                    + "&grant_type=authorization_code";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_ENDPOINT))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = mapper.readValue(resp.body(), Map.class);

            if (obj.containsKey("error")) {
                System.err.println("[GoogleAuth] Google token error: " + obj.get("error") + " — " + obj.get("error_description"));
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Google rejected the sign-in request"));
            }

            String idToken = (String) obj.getOrDefault("id_token", "");
            String accessToken = (String) obj.getOrDefault("access_token", "");
            if (idToken.isEmpty() && accessToken.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "Google returned no tokens"));
            }

            System.out.println("[GoogleAuth] Token exchange OK — idToken=" + !idToken.isEmpty() + " accessToken=" + !accessToken.isEmpty());
            return ResponseEntity.ok(Map.of("idToken", idToken, "accessToken", accessToken));
        } catch (Exception e) {
            System.err.println("[GoogleAuth] Exchange failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Token exchange failed"));
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
