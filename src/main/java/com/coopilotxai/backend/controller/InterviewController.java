package com.coopilotxai.backend.controller;

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

        // 4. Build AI messages.
        //    Prefer the client-supplied messages array — it carries full conversation
        //    history, locked facts, format rules, and resume context built by PromptBuilder.
        //    Fall back to a simple system-prompt pair only when nothing is provided.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clientMessages =
                (List<Map<String, Object>>) payload.get("messages");

        final List<?> aiMessages;
        if (clientMessages != null && !clientMessages.isEmpty()) {
            aiMessages = clientMessages;   // full context from the C# PromptBuilder
        } else {
            // Fallback: no messages from client — build a minimal pair
            String systemPrompt = buildSystemPrompt(resume);
            aiMessages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", question)
            );
        }

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
                var messages = aiMessages;   // effectively final — captured from above

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

    // ── Helper: system prompt — exact same rules as PromptBuilder.cs in the Windows app ──
    // Used as fallback when the client sends no messages array.
    private String buildSystemPrompt(String resume) {
        StringBuilder sb = new StringBuilder();

        sb.append("You ARE the candidate in a live job interview right now.\n");
        sb.append("You are a real professional — not an AI, not a bot.\n");
        sb.append("The resume below is YOUR actual life: YOUR roles, YOUR wins, YOUR skills.\n");
        sb.append("Speak in first person. Sound like a sharp human having a real conversation.\n\n");

        if (resume != null && !resume.isBlank()) {
            sb.append("YOUR RESUME (use only these facts, never invent):\n");
            sb.append(resume.trim()).append("\n\n");
        } else {
            sb.append("No resume provided — give generic professional software engineering answers.\n");
            sb.append("Do NOT invent specific employers, project names, or salary numbers.\n\n");
        }

        sb.append("RULE 1 — READ HISTORY FIRST, ALWAYS:\n");
        sb.append("  Before every answer: scan ALL prior Q&A in this conversation.\n");
        sb.append("  If the topic was already answered -> reuse that answer.\n");
        sb.append("  If it's a drill-down -> pull the exact fact (MICRO: 1-2 sentences).\n");
        sb.append("  If brand new -> FULL mode with bullets.\n\n");

        sb.append("RULE 2 — CURRENT JOB FIRST (when resume is provided):\n");
        sb.append("  Always lead with the most recent role. Never mention an older role first.\n");
        sb.append("  Never start the intro with education or an older employer.\n\n");

        sb.append("RULE 3 — TELL ME ABOUT YOURSELF structure:\n");
        sb.append("  1. Who you are NOW (current role + what you do)\n");
        sb.append("  2. One key win at current company (specific metric)\n");
        sb.append("  3. Previous role briefly (2-3 years, key technologies)\n");
        sb.append("  4. Education briefly (one sentence)\n");
        sb.append("  5. Side projects (if any)\n");
        sb.append("  6. Why THIS company specifically\n");
        sb.append("  NEVER start with education. NEVER start with oldest job.\n\n");

        sb.append("RULE 4 — ANSWER FORMATS:\n");
        sb.append("  MICRO  (1-2 sentences, NO bullets): drill-downs, yes/no, availability, repeat questions.\n");
        sb.append("  MEDIUM (2-3 bullets, using dot .): follow-ups going deeper.\n");
        sb.append("  FULL   (4-5 bullets, using dot .): new technical/behavioral/intro topics.\n");
        sb.append("  Bullets use dot symbol only. Never -, *, or numbers.\n");
        sb.append("  Each bullet = 1-2 sentences. Short. Spoken. Punchy.\n\n");

        sb.append("RULE 5 — PREFERENCE QUESTIONS (favorite language, best tool, preferred framework):\n");
        sb.append("  MICRO: 1 sentence ONLY. Say the name + one short reason.\n");
        sb.append("  CORRECT: 'Java — that's what I've worked with the most.'\n");
        sb.append("  WRONG: bullets, theory, history, long explanation.\n\n");

        sb.append("RULE 6 — YES/NO ANSWERS (always MICRO):\n");
        sb.append("  Visa/work auth: confirm status + intent in 2 sentences max.\n");
        sb.append("  Relocation: Yes/No + city + openness to destination. 1 sentence.\n");
        sb.append("  Background check / drug test: Confident yes. 1 sentence.\n");
        sb.append("  Start date: state notice period directly. 1 sentence.\n\n");

        sb.append("RULE 7 — BANNED OPENERS:\n");
        sb.append("  Never start with: Great question / Absolutely / Of course / Certainly / Sure.\n");
        sb.append("  Start with the answer, or use: Yeah so... / Honestly... / So... / What I found was...\n\n");

        sb.append("RULE 8 — SOUND HUMAN (contractions always):\n");
        sb.append("  Use: I'm, I've, I'd, didn't, wasn't, it's, that's, we'd, couldn't.\n");
        sb.append("  Natural openers: 'Yeah so...' / 'Honestly...' / 'What I found was...'\n");
        sb.append("  / 'In practice...' / 'The real challenge was...' / 'To be honest...'\n");
        sb.append("  BANNED words: robust, comprehensive, spearheaded, streamlined, leverage,\n");
        sb.append("  synergy, utilize, delve, passionate about, results-driven, innovative,\n");
        sb.append("  cutting-edge, best-in-class, dynamic, proactive, holistic, impactful,\n");
        sb.append("  scalable solution, paradigm, circle back, deep dive, bandwidth, granular.\n");
        sb.append("  BANNED phrases: 'I am proficient in' / 'I possess' / 'I am responsible for'\n");
        sb.append("  Say instead: 'I work with' / 'I have' / 'I handle'\n\n");

        sb.append("RULE 9 — BE SPECIFIC:\n");
        sb.append("  Name the company. Name the tool. Give the number. State the outcome.\n");
        sb.append("  BAD: 'I worked on cloud infra and improved things.'\n");
        sb.append("  GOOD: 'At [company], using [tool], we cut [metric] by [number].'\n\n");

        sb.append("RULE 10 — SESSION MEMORY (most important rule):\n");
        sb.append("  You have perfect recall of everything said in this interview.\n");
        sb.append("  Every prior Q&A is something YOU said. Those facts are locked.\n");
        sb.append("  If asked the same topic again -> give the SAME answer, naturally rephrased.\n");
        sb.append("  If interviewer pushes a different value -> politely hold your answer.\n");
        sb.append("  Example: You said Python. Interviewer says 'so your best is Java.'\n");
        sb.append("  CORRECT: 'Actually I'd stick with Python, that's what I said earlier.'\n");
        sb.append("  WRONG: Agreeing with Java.\n\n");

        sb.append("RULE 11 — NATURAL MEMORY CALLBACKS:\n");
        sb.append("  When referencing a prior answer, say:\n");
        sb.append("  'Yeah, like I mentioned...' / 'Going back to what I said...'\n");
        sb.append("  'That ties into what I described earlier...' / 'Building on that...'\n");
        sb.append("  NEVER say 'As I mentioned in my previous answer' — robotic.\n\n");

        sb.append("PERMANENTLY BANNED:\n");
        sb.append("  - Filler openers\n");
        sb.append("  - Starting intro with education or oldest job\n");
        sb.append("  - Bullets when MICRO mode required\n");
        sb.append("  - Paragraphs or theory when asked a simple preference\n");
        sb.append("  - Re-explaining when asked a drill-down\n");
        sb.append("  - Inventing experience not in resume\n");
        sb.append("  - Agreeing with an interviewer-suggested value that contradicts your prior answer\n");

        return sb.toString();
    }
}