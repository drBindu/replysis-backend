package com.coopilotxai.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Value("${google.client.id}")
    private String googleClientId;

    @Value("${google.client.secret}")
    private String googleClientSecret;

    private static final String FIREBASE_WEB_API_KEY = "AIzaSyAGGmuFpR0qkCHLI3q2cPv_o3cQlbIU8lE";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/google/exchange")
    public ResponseEntity<Map<String, Object>> exchangeGoogleCode(
            @RequestBody Map<String, String> body) {

        String code         = body.get("code");
        String codeVerifier = body.get("codeVerifier");
        String redirectUri  = body.get("redirectUri");

        if (code == null || redirectUri == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required fields: code, redirectUri"));
        }

        try {
            // ── 1. Exchange auth code with Google ──────────────────
            StringBuilder form = new StringBuilder();
            form.append("code=").append(enc(code));
            form.append("&client_id=").append(enc(googleClientId));
            form.append("&client_secret=").append(enc(googleClientSecret));
            form.append("&redirect_uri=").append(enc(redirectUri));
            form.append("&grant_type=authorization_code");
            if (codeVerifier != null)
                form.append("&code_verifier=").append(enc(codeVerifier));

            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();

            HttpResponse<String> tokenRes = HTTP.send(tokenReq,
                    HttpResponse.BodyHandlers.ofString());

            if (tokenRes.statusCode() < 200 || tokenRes.statusCode() >= 300) {
                return ResponseEntity.status(502)
                        .body(Map.of("error", "Google token exchange failed",
                                     "detail", tokenRes.body()));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> tokenBody = MAPPER.readValue(tokenRes.body(), Map.class);
            String accessToken = (String) tokenBody.get("access_token");

            if (accessToken == null) {
                return ResponseEntity.status(502)
                        .body(Map.of("error", "No access_token in Google response"));
            }

            // ── 2. Sign into Firebase via Google access_token ──────
            Map<String, Object> idpPayload = new HashMap<>();
            idpPayload.put("postBody",
                    "access_token=" + enc(accessToken) + "&providerId=google.com");
            idpPayload.put("requestUri",          "http://localhost");
            idpPayload.put("returnIdpCredential", true);
            idpPayload.put("returnSecureToken",   true);

            HttpRequest idpReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key="
                            + FIREBASE_WEB_API_KEY))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(idpPayload)))
                    .build();

            HttpResponse<String> idpRes = HTTP.send(idpReq,
                    HttpResponse.BodyHandlers.ofString());

            if (idpRes.statusCode() < 200 || idpRes.statusCode() >= 300) {
                return ResponseEntity.status(502)
                        .body(Map.of("error", "Firebase sign-in failed",
                                     "detail", idpRes.body()));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> idpBody = MAPPER.readValue(idpRes.body(), Map.class);

            // ── 3. Return Firebase credentials to the client ───────
            Map<String, Object> result = new HashMap<>();
            result.put("idToken",      idpBody.get("idToken"));
            result.put("refreshToken", idpBody.get("refreshToken"));
            result.put("email",        idpBody.get("email"));
            result.put("displayName",  idpBody.get("displayName"));
            result.put("localId",      idpBody.get("localId"));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Internal error", "detail", e.getMessage()));
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
