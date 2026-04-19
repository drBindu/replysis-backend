package com.coopilotxai.backend.controller;

import com.coopilotxai.backend.security.FirebaseAuthFilter;
import com.coopilotxai.backend.security.FirebaseAuthService;
import com.coopilotxai.backend.security.AuthUser;
import com.coopilotxai.backend.service.FirestoreCreditsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/interview")
@CrossOrigin(origins = "*")
public class InterviewController {

    @Autowired
    private FirebaseAuthService firebaseAuthService;

    @Autowired
    private FirestoreCreditsService creditsService;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    private static final String GROQ_ENDPOINT   = "https://api.groq.com/openai/v1/chat/completions";
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL   = "llama-3.3-70b-versatile";
    private static final int    COST_PER_QUESTION = 5;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ── GET /api/v1/interview/credits ────────────────────────────────────────
    // Returns current credit balance for the authenticated user
    @GetMapping("/credits")
    public ResponseEntity<?> getCredits(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing token"));

        FirestoreCreditsService.UserCredits credits = creditsService.getCredits(user.uid());
        return ResponseEntity.ok(Map.of(
                "credits",     credits.credits,
                "plan",        credits.plan,
                "isUnlimited", credits.isUnlimited
        ));
    }

    // ── POST /api/v1/interview/ask ───────────────────────────────────────────
    // Main endpoint: verify token → check credits → call AI → deduct → return
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> askQuestion(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> payload) {

        // 1. Verify Firebase token
        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        // 2. Check credits
        if (!creditsService.canAfford(user.uid())) {
            return ResponseEntity.status(402).build(); // Payment Required
        }

        // 3. Extract payload
        String question  = (String) payload.getOrDefault("question", "");
        String resume    = (String) payload.getOrDefault("resume", "");
        String provider  = (String) payload.getOrDefault("provider", "groq");

        if (question == null || question.trim().isEmpty())
            return ResponseEntity.badRequest().build();

        // 4. Build system prompt
        String systemPrompt = buildSystemPrompt(resume);

        // 5. Build AI request
        String endpoint = provider.equals("openai") ? OPENAI_ENDPOINT : GROQ_ENDPOINT;
        String apiKey   = provider.equals("openai") ? openAiApiKey    : groqApiKey;
        String model    = provider.equals("openai") ? "gpt-4o"        : DEFAULT_MODEL;

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("No API key for provider: " + provider);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // 6. Stream response
        StreamingResponseBody stream = outputStream -> {
            boolean deducted = false;
            try {
                var messages = List.of(
                    Map.of("role", "system",  "content", systemPrompt),
                    Map.of("role", "user",    "content", question)
                );

                var aiPayload = Map.of(
                    "model",             model,
                    "messages",          messages,
                    "temperature",       0.2,
                    "max_tokens",        700,
                    "stream",            true,
                    "top_p",             0.95,
                    "frequency_penalty", 0.3,
                    "presence_penalty",  0.15
                );

                String body = mapper.writeValueAsString(aiPayload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<java.io.InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    System.err.println("AI error: HTTP " + response.statusCode());
                    String err = "data: {\"error\":\"AI service error " + response.statusCode() + "\"}\n\n";
                    outputStream.write(err.getBytes());
                    outputStream.flush();
                    return;
                }

                // Deduct credits now that AI responded successfully
                deducted = creditsService.deductCredits(user.uid());
                System.out.println("Credits deducted for uid=" + user.uid() + " success=" + deducted);

                // Stream tokens back to WPF
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            outputStream.write((line + "\n\n").getBytes());
                            outputStream.flush();
                        }
                    }
                }

            } catch (Exception e) {
                System.err.println("Stream error: " + e.getMessage());
                try {
                    String err = "data: {\"error\":\"" + e.getMessage() + "\"}\n\n";
                    outputStream.write(err.getBytes());
                    outputStream.flush();
                } catch (Exception ignored) {}
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    // ── Helper: verify Firebase Bearer token ────────────────────────────────
    private AuthUser verifyToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            String token = authHeader.substring("Bearer ".length()).trim();
            FirebaseToken decoded = firebaseAuthService.verify(token);
            return new AuthUser(decoded.getUid(), decoded.getEmail(), decoded.getName());
        } catch (Exception e) {
            System.err.println("Token verification failed: " + e.getMessage());
            return null;
        }
    }

    // ── Helper: build system prompt ──────────────────────────────────────────
    private String buildSystemPrompt(String resume) {
        String base = """
            You are an expert interview coach helping a candidate in a live job interview.
            Give sharp, confident, structured answers using the STAR method where relevant.
            Be concise — 3 to 5 sentences max unless the question requires detail.
            Never mention you are an AI. Respond as if YOU are the candidate.
            Use first person (I, my, we).
            Do NOT use markdown, bullet symbols, or special characters.
            Write in plain flowing prose.
            """;

        if (resume != null && !resume.isBlank()) {
            return base + "\n\nCandidate's background:\n" + resume.trim();
        }
        return base;
    }
}