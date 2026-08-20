package com.replysis.backend.controller;

import com.replysis.backend.security.IdentityResolverService;
import com.replysis.backend.security.RequestIdentity;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.FirestoreCreditsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/interview")
public class InterviewController {

    @Autowired
    private IdentityResolverService identityResolver;

    @Autowired
    private FirestoreCreditsService creditsService;

    @Autowired
    private SimpleRateLimiter rateLimiter;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    private static final String GROQ_ENDPOINT   = "https://api.groq.com/openai/v1/chat/completions";
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    // Groq shut down llama-3.1-8b-instant on 2026-08-16 and names gpt-oss-20b as
    // its replacement. Keeping the small, fast model here on purpose: answers
    // stream while the candidate is still being asked the question.
    private static final String DEFAULT_MODEL   = "openai/gpt-oss-20b";
    private static final String VISION_MODEL_OPENAI = "gpt-4o";
    private static final int    COST_PER_QUESTION = 5;
    private static final int    MAX_QUESTION_CHARS = 4_000;
    // The screen-analysis prompt is not user-typed text — it is a fixed template
    // the app builds itself (problem/approach/code/tests/complexity/explanation
    // sections for coding and system-design screens). Measured at ~6,445 chars
    // and growing as sections are added, it was silently rejected by the 4,000
    // char question ceiling: every screen analysis request failed with a 400
    // and no log line, because MAX_QUESTION_CHARS was reused here instead of a
    // limit sized for what this endpoint actually receives.
    private static final int    MAX_SCREEN_PROMPT_CHARS = 20_000;
    private static final int    MAX_RESUME_CHARS = 30_000;
    // Abuse ceilings, not size targets. The client's system message carries the
    // resume (up to MAX_RESUME_CHARS) plus locked facts and format rules, so it
    // is routinely far larger than a chat turn: a real request was measured at
    // 10,638 bytes across 2 messages and a 6,000-char cap silently rejected it.
    // A long interview also accumulates history, so the count must leave room.
    private static final int    MAX_MESSAGE_COUNT = 100;
    private static final int    MAX_MESSAGE_CHARS = 60_000;
    private static final int    MAX_IMAGE_BASE64_CHARS = 8_000_000;
    private static final int    PER_IDENTITY_PER_MINUTE = 12;
    private static final int    PER_IP_PER_MINUTE = 30;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ── GET /api/v1/interview/credits ────────────────────────────────────────
    // Returns current credit balance for the authenticated user, or — with no
    // Authorization header but an X-Device-Id header instead — the free guest
    // trial balance for that hardware device (see resolveIdentity()).
    @GetMapping("/credits")
    public ResponseEntity<?> getCredits(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {

        RequestIdentity identity = identityResolver.resolve(authHeader, deviceId);
        if (identity == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing token"));

        FirestoreCreditsService.UserCredits credits = identity.isGuest()
                ? creditsService.getGuestCredits(identity.deviceId())
                : creditsService.getCredits(identity.uid());
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
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest request,
            @RequestBody Map<String, Object> payload) {

        // 1. Verify Firebase token, or fall back to the free guest trial by device ID
        RequestIdentity identity = identityResolver.resolve(authHeader, deviceId);
        if (identity == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        if (!allowExpensiveRequest(identity, request, "ask"))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();

        // 3. Validate every client-controlled input before it reaches an AI
        // provider. This caps spend, rejects malformed message objects, and
        // keeps provider selection on a server-side allow-list.
        String question = text(payload.get("question"), MAX_QUESTION_CHARS);
        String resume = textOrEmpty(payload.get("resume"), MAX_RESUME_CHARS);
        String providerInput = textOrEmpty(payload.get("provider"), 20);
        if (providerInput == null) return rejectAsk("provider field was not usable text");
        final String provider = providerInput.isBlank() ? "groq" : providerInput.toLowerCase();

        if (question == null || question.isBlank())
            return rejectAsk("question missing, blank, or longer than " + MAX_QUESTION_CHARS + " chars");
        if (resume == null)
            return rejectAsk("resume longer than " + MAX_RESUME_CHARS + " chars");
        if (!provider.equals("groq") && !provider.equals("openai"))
            return rejectAsk("provider not on the allow-list");

        // 4. Build AI messages.
        //    Prefer the client-supplied messages array — it carries full conversation
        //    history, locked facts, format rules, and resume context built by PromptBuilder.
        //    Fall back to a simple system-prompt pair only when nothing is provided.
        Object rawMessages = payload.get("messages");
        List<Map<String, Object>> clientMessages = sanitizeMessages(rawMessages);
        if (rawMessages != null && clientMessages == null)
            return rejectAsk("messages array rejected: over " + MAX_MESSAGE_COUNT
                    + " messages, a message over " + MAX_MESSAGE_CHARS
                    + " chars, a blank message, or a bad role/shape");

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

        // Alternate provider — used as a fallback if the primary is rate-limited/unavailable.
        // The SAME aiMessages are reused, so conversation context/prompt stays identical either way.
        String fallbackProvider = provider.equals("openai") ? "groq" : "openai";
        String fallbackEndpoint = fallbackProvider.equals("openai") ? OPENAI_ENDPOINT : GROQ_ENDPOINT;
        String fallbackApiKey   = fallbackProvider.equals("openai") ? openAiApiKey    : groqApiKey;
        String fallbackModel    = fallbackProvider.equals("openai") ? "gpt-4o"        : DEFAULT_MODEL;

        // 6. Charge UP FRONT (atomic). Deducting after the AI answered meant a
        //    failed deduction still served a free answer; charging first closes
        //    that hole. If every provider fails below, the charge is refunded.
        boolean charged = identity.isGuest()
                ? creditsService.deductGuestCredits(identity.deviceId())
                : creditsService.deductCredits(identity.uid());
        if (!charged) {
            return ResponseEntity.status(402).build(); // Payment Required
        }

        // 7. Stream response
        StreamingResponseBody stream = outputStream -> {
            boolean providerAccepted = false;
            boolean answerDelivered = false;
            try {
                var messages = aiMessages;   // effectively final — captured from above

                HttpResponse<java.io.InputStream> response = callAiProvider(endpoint, apiKey, model, messages);

                // Rate limit / server error from the upstream provider is usually transient —
                // retry once after a short backoff before giving up on this provider.
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    long waitMs = retryDelayMs(response);
                    System.err.println("Provider " + provider + " returned HTTP " + response.statusCode()
                            + ", retrying in " + waitMs + "ms");
                    Thread.sleep(waitMs);
                    response = callAiProvider(endpoint, apiKey, model, messages);
                }

                // Still failing — fall back to the other configured provider with the SAME
                // messages, so the user gets a real answer instead of a raw error.
                if (response.statusCode() != 200 && fallbackApiKey != null && !fallbackApiKey.isBlank()) {
                    System.err.println("Provider " + provider + " returned HTTP " + response.statusCode()
                            + ", falling back to " + fallbackProvider);
                    response = callAiProvider(fallbackEndpoint, fallbackApiKey, fallbackModel, messages);
                }

                if (response.statusCode() != 200) {
                    System.err.println("AI error: HTTP " + response.statusCode());
                    outputStream.write(friendlyErrorEvent().getBytes());
                    outputStream.flush();
                    return;
                }

                providerAccepted = true;

                // A refusal must never reach the person waiting to speak. It is
                // held back, the question is asked again without the framing the
                // model objected to, and only the real answer is sent.
                //
                // Doing this here rather than in the app means it costs one
                // credit instead of two, and it covers the builds already
                // installed, which cannot retry and were showing the apology.
                answerDelivered = streamUnlessRefused(response, outputStream);

                if (!answerDelivered) {
                    System.out.println("[AI] Model declined; asking again in plainer words.");
                    HttpResponse<java.io.InputStream> retry =
                            callAiProvider(endpoint, apiKey, model, plainRetryMessages(aiMessages));
                    if (retry.statusCode() == 200)
                        answerDelivered = streamUnlessRefused(retry, outputStream);
                }
            } catch (Exception e) {
                System.err.println("Stream error: " + e.getMessage());
                try {
                    outputStream.write(friendlyErrorEvent().getBytes());
                    outputStream.flush();
                } catch (Exception ignored) {}
            } finally {
                // No answer was delivered — return the up-front charge.
                if (!providerAccepted || !answerDelivered) {
                    if (identity.isGuest()) creditsService.refundGuestCredits(identity.deviceId());
                    else                    creditsService.refundCredits(identity.uid());
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    // ── POST /api/v1/interview/analyze-screen ────────────────────────────────
    // Vision analysis: verify token → check credits → call vision model → deduct → return
    @PostMapping(value = "/analyze-screen", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> analyzeScreen(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest request,
            @RequestBody Map<String, Object> payload) {

        RequestIdentity identity = identityResolver.resolve(authHeader, deviceId);
        if (identity == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        if (!allowExpensiveRequest(identity, request, "vision"))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();

        String image = text(payload.get("image"), MAX_IMAGE_BASE64_CHARS);
        String prompt = text(payload.get("prompt"), MAX_SCREEN_PROMPT_CHARS);
        String providerInput = textOrEmpty(payload.get("provider"), 20);
        if (providerInput == null) return rejectScreen("provider field was not usable text");
        final String provider = providerInput.isBlank() ? "groq" : providerInput.toLowerCase();

        if (image == null || image.isBlank())
            return rejectScreen("image missing, blank, not valid base64, or longer than " + MAX_IMAGE_BASE64_CHARS + " chars");
        if (prompt == null || prompt.isBlank())
            return rejectScreen("prompt missing, blank, or longer than " + MAX_SCREEN_PROMPT_CHARS + " chars");
        if (!isBase64(image))
            return rejectScreen("image was not valid base64");
        if (!provider.equals("groq") && !provider.equals("openai"))
            return rejectScreen("provider not on the allow-list");

        // Vision runs on OpenAI whatever the caller asks for. Groq's vision model
        // (llama-4-scout) was retired on 2026-07-17 and now answers
        // model_not_found, so honouring a "groq" request here only bought a
        // guaranteed failure and a wasted round trip before the fallback. Restore
        // provider choice once a Groq vision model is confirmed available.
        String endpoint = OPENAI_ENDPOINT;
        String apiKey   = openAiApiKey;
        String model    = VISION_MODEL_OPENAI;

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("No API key for provider: " + provider);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // One retry against the same provider. This used to fall back to Groq,
        // which for vision meant retrying into a model that no longer exists.
        String fallbackProvider = "openai";
        String fallbackEndpoint = OPENAI_ENDPOINT;
        String fallbackApiKey   = openAiApiKey;
        String fallbackModel    = VISION_MODEL_OPENAI;

        final String finalImage  = image;
        final String finalPrompt = prompt;

        // Charge UP FRONT (atomic) — same contract as /ask: no unpaid answers,
        // and a refund below if every vision provider fails.
        boolean charged = identity.isGuest()
                ? creditsService.deductGuestCredits(identity.deviceId())
                : creditsService.deductCredits(identity.uid());
        if (!charged) {
            return ResponseEntity.status(402).build(); // Payment Required
        }

        StreamingResponseBody stream = outputStream -> {
            boolean providerAccepted = false;
            boolean answerDelivered = false;
            try {
                List<Map<String, Object>> messages = buildVisionMessages(finalImage, finalPrompt);

                HttpResponse<java.io.InputStream> response = callVisionProvider(endpoint, apiKey, model, messages);

                // Rate limit / server error from the upstream provider is usually transient —
                // retry once after a short backoff before giving up on this provider.
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    long waitMs = retryDelayMs(response);
                    System.err.println("Vision provider " + provider + " returned HTTP "
                            + response.statusCode() + ", retrying in " + waitMs + "ms");
                    Thread.sleep(waitMs);
                    response = callVisionProvider(endpoint, apiKey, model, messages);
                }

                // Still failing — one last attempt. Both paths are OpenAI now, so
                // the same messages are reused rather than rebuilt.
                if (response.statusCode() != 200 && fallbackApiKey != null && !fallbackApiKey.isBlank()) {
                    System.err.println("Vision provider " + provider + " returned HTTP " + response.statusCode()
                            + ", falling back to " + fallbackProvider);
                    response = callVisionProvider(fallbackEndpoint, fallbackApiKey, fallbackModel, messages);
                }

                if (response.statusCode() != 200) {
                    System.err.println("Vision AI error: HTTP " + response.statusCode());
                    outputStream.write(friendlyErrorEvent().getBytes());
                    outputStream.flush();
                    return;
                }

                providerAccepted = true;

                // A refusal must never reach the person waiting to speak. It is
                // held back, the question is asked again without the framing the
                // model objected to, and only the real answer is sent.
                //
                // Doing this here rather than in the app means it costs one
                // credit instead of two, and it covers the builds already
                // installed, which cannot retry and were showing the apology.
                answerDelivered = streamUnlessRefused(response, outputStream);

                if (!answerDelivered) {
                    System.out.println("[VISION] Model declined; asking again in plainer words.");
                    List<Map<String, Object>> plain = buildVisionMessages(finalImage, PLAIN_VISION_PROMPT);
                    HttpResponse<java.io.InputStream> retry =
                            callVisionProvider(endpoint, apiKey, model, plain);
                    if (retry.statusCode() == 200)
                        answerDelivered = streamUnlessRefused(retry, outputStream);
                }
            } catch (Exception e) {
                System.err.println("Screen analysis stream error: " + e.getMessage());
                try {
                    outputStream.write(friendlyErrorEvent().getBytes());
                    outputStream.flush();
                } catch (Exception ignored) {}
            } finally {
                // No answer was delivered — return the up-front charge.
                if (!providerAccepted || !answerDelivered) {
                    if (identity.isGuest()) creditsService.refundGuestCredits(identity.deviceId());
                    else                    creditsService.refundCredits(identity.uid());
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    // ── Helper: build the provider-specific vision "messages" array ──────────
    // OpenAI supports the "detail" field on image_url; Groq's vision API rejects it.
    private boolean allowExpensiveRequest(RequestIdentity identity, HttpServletRequest request, String endpoint) {
        String identityKey = identity.isGuest() ? "guest:" + identity.deviceId() : "user:" + identity.uid();
        String ip = rateLimiter.clientIp(request);
        return rateLimiter.tryAcquire(endpoint + ":ip:" + ip, PER_IP_PER_MINUTE, 60_000L)
                && rateLimiter.tryAcquire(endpoint + ":identity:" + identityKey, PER_IDENTITY_PER_MINUTE, 60_000L);
    }

    private static String text(Object value, int maximumLength) {
        if (!(value instanceof String result)) return null;
        if (result.length() > maximumLength) return null;
        return result.trim();
    }

    private static String textOrEmpty(Object value, int maximumLength) {
        if (value == null) return "";
        return text(value, maximumLength);
    }

    /**
     * A validation rejection used to return 400 with no trace at all, so a user
     * saw "we could not generate an answer" while the server logged nothing.
     * The reason is recorded here; it names the failed rule and sizes only,
     * never any question, resume, or message content.
     */
    private static ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> rejectAsk(String reason) {
        System.out.println("[ASK] Rejected (400): " + reason);
        return ResponseEntity.badRequest().build();
    }

    /**
     * Same purpose as rejectAsk: a 400 here used to return with no trace at all,
     * so a screen-analysis request could fail with every retry looking identical
     * from the outside. This is exactly what let MAX_QUESTION_CHARS silently
     * reject every real prompt for as long as it did.
     */
    private static ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> rejectScreen(String reason) {
        System.out.println("[ANALYZE_SCREEN] Rejected (400): " + reason);
        return ResponseEntity.badRequest().build();
    }

    private static List<Map<String, Object>> sanitizeMessages(Object rawMessages) {
        if (rawMessages == null) return null;
        if (!(rawMessages instanceof List<?> messages) || messages.isEmpty() || messages.size() > MAX_MESSAGE_COUNT) {
            return null;
        }

        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Object rawMessage : messages) {
            if (!(rawMessage instanceof Map<?, ?> message)) return null;
            Object rawRole = message.get("role");
            Object rawContent = message.get("content");
            if (!(rawRole instanceof String role) || !(rawContent instanceof String content)
                    || (!role.equals("system") && !role.equals("user") && !role.equals("assistant"))
                    || content.isBlank() || content.length() > MAX_MESSAGE_CHARS) {
                return null;
            }
            sanitized.add(Map.of("role", role, "content", content.trim()));
        }
        return sanitized;
    }

    private static boolean isBase64(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean allowed = (current >= 'A' && current <= 'Z')
                    || (current >= 'a' && current <= 'z')
                    || (current >= '0' && current <= '9')
                    || current == '+' || current == '/' || current == '='
                    || current == '\r' || current == '\n';
            if (!allowed) return false;
        }
        return true;
    }

    /// Builds the vision request body.
    ///
    /// The detail flag is not optional for this product. Without it OpenAI
    /// decides for itself how closely to look, and the thing being sent is a
    /// screenshot of a coding problem, where the difference between reading and
    /// guessing is a few pixels per character. "high" makes it tile the image and
    /// read all of it.
    ///
    /// It used to be attached only when the caller asked for "openai". That test
    /// stopped meaning anything when vision was pinned to OpenAI regardless of
    /// what the caller asked for, and the desktop app defaults to asking for
    /// "groq", so in practice almost every screenshot was sent without it. The
    /// flag now follows where the request actually goes, not what the caller
    /// named.
    private List<Map<String, Object>> buildVisionMessages(String base64Image, String prompt) {
        Map<String, Object> imageUrl =
                Map.of("url", "data:image/png;base64," + base64Image, "detail", "high");

        Map<String, Object> imageContent = Map.of("type", "image_url", "image_url", imageUrl);
        Map<String, Object> textContent  = Map.of("type", "text", "text", prompt);

        return List.of(Map.of("role", "user", "content", List.of(textContent, imageContent)));
    }

    // ── Helper: call a vision-capable chat-completions endpoint ───────────────
    private HttpResponse<java.io.InputStream> callVisionProvider(
            String endpoint, String apiKey, String model, List<?> messages) throws Exception {

        var aiPayload = Map.of(
            "model",      model,
            "messages",   messages,
            "max_tokens", 4096,
            "stream",     true
        );

        String body = mapper.writeValueAsString(aiPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    // ── Helper: call an AI provider's chat-completions endpoint with the given messages ──
    private HttpResponse<java.io.InputStream> callAiProvider(
            String endpoint, String apiKey, String model, List<?> messages) throws Exception {

        var aiPayload = new java.util.HashMap<String, Object>(Map.of(
            "model",             model,
            "messages",          messages,
            "temperature",       0.2,
            "max_tokens",        700,
            "stream",            true,
            "top_p",             0.95,
            "frequency_penalty", 0.3,
            "presence_penalty",  0.15
        ));

        // gpt-oss streams a hidden "reasoning" field before any answer text, and
        // it is billed against max_tokens. Measured on the default setting: the
        // first word of the answer arrived 372 chunks in, and the answer itself
        // was cut short because reasoning had spent the budget. On "low" it
        // starts at chunk 30 and returns roughly 2.6x more answer for the same
        // tokens. This path exists to put words on screen while the candidate is
        // still being asked, so the trade is worth it.
        // "low" was as far as reasoning_effort goes on gpt-oss, and it still
        // reasons: 30 chunks of it before the first word of the answer. Turning
        // the stream off outright is a separate switch, and it is the one that
        // matters here, because the wait a candidate feels is the wait before
        // words appear, not the rate they appear at afterwards.
        //
        // Which is why gpt-oss felt slower than the llama-instant it replaced
        // despite being the faster model on paper: 1,000 tokens a second against
        // 560, spent thinking where nobody could see it. Throughput was never the
        // number to optimise for a person waiting to speak.
        if (model.startsWith("openai/gpt-oss")) {
            aiPayload.put("reasoning_effort", "low");
            aiPayload.put("include_reasoning", false);
        }

        String body = mapper.writeValueAsString(aiPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    // ── Helper: SSE chunk shaped like a normal answer token ─────────────────
    // Used when every provider is unavailable, so the AI Answer box shows a
    // graceful in-character message instead of a raw "AI service error" banner.
    /**
     * Streams one provider response, holding the opening back long enough to
     * tell whether the model is declining.
     *
     * A refusal has to be caught before any of it reaches the user, and it can
     * only be recognised once some of the answer has arrived, so the first few
     * lines are buffered rather than forwarded. If the answer is real those
     * lines are released immediately and the rest flows straight through; if it
     * is a refusal nothing is written and the caller gets to ask again. The held
     * back portion is tens of characters, which at streaming speed is not a
     * visible pause.
     *
     * Returns true when an answer was delivered, false when the model declined
     * or said nothing.
     */
    private boolean streamUnlessRefused(HttpResponse<java.io.InputStream> response,
                                        java.io.OutputStream outputStream) throws Exception {
        StringBuilder opening = new StringBuilder();
        List<String> held = new java.util.ArrayList<>();
        boolean released = false, delivered = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;

                if (released) {
                    if (hasContentToken(line)) delivered = true;
                    outputStream.write((line + "\n\n").getBytes());
                    outputStream.flush();
                    continue;
                }

                held.add(line);
                if (hasContentToken(line)) {
                    delivered = true;
                    opening.append(contentToken(line));
                }
                if (opening.length() < REFUSAL_PROBE_CHARS) continue;

                if (looksLikeRefusal(opening.toString())) return false;

                released = true;
                for (String h : held) outputStream.write((h + "\n\n").getBytes());
                outputStream.flush();
                held.clear();
            }
        }

        // Ended before the probe filled, so nothing has been written yet.
        if (!released) {
            if (looksLikeRefusal(opening.toString())) return false;
            for (String h : held) outputStream.write((h + "\n\n").getBytes());
            outputStream.flush();
        }
        return delivered;
    }

    /**
     * The same request stripped of everything a model can object to: the role
     * play, the interview framing, the instructions about who is speaking. Used
     * only after a refusal, because that framing is what was refused, and an
     * answer in a plainer voice beats an apology.
     */
    private List<Map<String, Object>> plainRetryMessages(List<?> original) {
        String question = "";
        for (Object m : original) {
            if (m instanceof Map<?, ?> msg && "user".equals(String.valueOf(msg.get("role")))) {
                question = String.valueOf(msg.get("content"));
            }
        }
        return List.of(
            Map.of("role", "system", "content",
                   "Answer the question directly and helpfully, in the first person, "
                 + "in two to four spoken sentences. Plain text, no headings."),
            Map.of("role", "user", "content", question));
    }

    /** How much of the answer to read before deciding it is a refusal. */
    private static final int REFUSAL_PROBE_CHARS = 64;

    /**
     * The screen request stripped of everything a model can object to. Used only
     * after a refusal, because the framing is what was refused, and an answer in
     * a plainer voice beats an apology to someone who has to speak in a moment.
     */
    private static final String PLAIN_VISION_PROMPT =
            "The image is a screenshot of the user's own screen. Describe what is on it "
          + "and answer any question visible in it.\n\n"
          + "Reply in this shape:\n\n"
          + "SAY THIS\n"
          + "The answer in the first person, two to four sentences, ready to read aloud.\n\n"
          + "DETAIL\n"
          + "Code, numbers or steps only if the answer needs them. Complete, never abbreviated.\n\n"
          + "SCREEN NOTES\n"
          + "One line listing what is visible: window name, menu and tab labels, buttons, "
          + "headings, figures. Facts only, comma separated.\n\n"
          + "Use only what is visible. Never claim you cannot see the image. Plain text.";

    private static final String[] REFUSAL_OPENINGS = {
        "i'm sorry", "i am sorry", "sorry, i can", "sorry, but i",
        "i can't help", "i cannot help", "i can't assist", "i cannot assist",
        "i'm not able to help", "i am unable to help", "i won't be able to help"
    };

    /** Whether an answer opens by declining. Refusals are short and start at the very beginning. */
    private static boolean looksLikeRefusal(String opening) {
        if (opening == null || opening.isBlank()) return false;
        String o = opening.stripLeading().toLowerCase();
        for (String r : REFUSAL_OPENINGS) if (o.startsWith(r)) return true;
        return false;
    }

    /** The text carried by one SSE delta, or "" when the line carries none. */
    private String contentToken(String sseLine) {
        try {
            String data = sseLine.substring("data: ".length()).trim();
            JsonNode choices = mapper.readTree(data).path("choices");
            if (!choices.isArray() || choices.isEmpty()) return "";
            return choices.get(0).path("delta").path("content").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasContentToken(String sseLine) {
        try {
            if (sseLine == null || !sseLine.startsWith("data: ")) return false;
            String data = sseLine.substring("data: ".length()).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) return false;

            JsonNode root = mapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return false;
            String content = choices.get(0).path("delta").path("content").asText("");
            return !content.isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * How long to wait before retrying a provider that just refused.
     *
     * A flat 1200ms was guesswork, and on Groq's free tier it is often too
     * short: the token bucket refills at about 133 a second, an interview
     * prompt is around 2,600 tokens, and a drained bucket therefore needs
     * closer to twenty seconds than one. The retry fired early, failed again,
     * and fell through to the other provider for nothing.
     *
     * Both Groq and OpenAI say how long to wait, in Retry-After or in the
     * x-ratelimit-reset-* headers. Asking is better than guessing.
     *
     * Capped at eight seconds. Somebody is sitting in an interview waiting to
     * speak, and past that point a slower answer stops being an answer.
     */
    private static long retryDelayMs(HttpResponse<?> response) {
        long fromHeader = Math.max(
                headerDelayMs(response, "retry-after"),
                Math.max(headerDelayMs(response, "x-ratelimit-reset-tokens"),
                         headerDelayMs(response, "x-ratelimit-reset-requests")));

        if (fromHeader <= 0) return 1_200L;              // no guidance: the old default
        return Math.min(Math.max(fromHeader + 150L, 300L), 8_000L);
    }

    /**
     * Reads a delay header. Retry-After is whole seconds; the rate-limit reset
     * headers use a compact duration such as "547ms", "1.5s" or "2m59.56s".
     */
    private static long headerDelayMs(HttpResponse<?> response, String name) {
        String raw = response.headers().firstValue(name).orElse("").trim();
        if (raw.isEmpty()) return -1;

        try {
            if (raw.matches("[0-9]+")) return Long.parseLong(raw) * 1_000L;   // Retry-After, seconds

            double ms = 0;
            var matcher = java.util.regex.Pattern
                    .compile("([0-9]*[.]?[0-9]+)(ms|s|m|h)")
                    .matcher(raw);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                double value = Double.parseDouble(matcher.group(1));
                ms += switch (matcher.group(2)) {
                    case "ms" -> value;
                    case "s"  -> value * 1_000;
                    case "m"  -> value * 60_000;
                    case "h"  -> value * 3_600_000;
                    default   -> 0;
                };
            }
            return found ? (long) ms : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String friendlyErrorEvent() throws Exception {
        var chunk = Map.of("error", "The AI service is temporarily unavailable. Please try again.");
        return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
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
