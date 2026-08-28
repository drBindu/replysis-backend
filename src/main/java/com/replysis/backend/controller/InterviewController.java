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

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    private static final String GROQ_ENDPOINT   = "https://api.groq.com/openai/v1/chat/completions";
    private static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    // Google's OpenAI-compatibility endpoint, not the native generateContent
    // API. It accepts the exact same {model, messages, stream} shape this file
    // already builds for Groq and OpenAI — including image_url data URIs and
    // SSE chunks shaped as data: {"choices":[{"delta":{"content":...}}]} — so
    // it drops into callVisionProvider/readScreenForCoding with zero format
    // translation. Verified against this endpoint directly before wiring it in.
    private static final String GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    // Groq shut down llama-3.1-8b-instant on 2026-08-16 and names gpt-oss-20b as
    // its replacement. Keeping the small, fast model here on purpose: answers
    // stream while the candidate is still being asked the question.
    private static final String DEFAULT_MODEL   = "openai/gpt-oss-20b";

    // The second Groq budget. Groq's rate limit is per model, so this is a
    // whole separate allowance on the same key, not a share of one.
    private static final String SECOND_CHOICE_MODEL = "openai/gpt-oss-120b";
    private static final String VISION_MODEL_OPENAI = "gpt-4o";

    // Groq has a vision model after all, and it is free on the same key.
    //
    // The comment below used to say no Groq vision model existed, and acted on
    // it: the whole screen path was hard-wired to OpenAI with OpenAI as its own
    // fallback. When that account lapsed, screen analysis did not degrade, it
    // died, and there was nothing behind it.
    //
    // The model list is not enough to tell. Asking each model for an image is:
    // compound, compound-mini and gpt-oss all answer "content must be a
    // string", and qwen3.6-27b answers "image must have at least 2 pixels in
    // each dimension" — a complaint about the test pixel, which means it read
    // the image. Given a real one it returns "blue", streams, and accepts
    // detail:high exactly as the app already sends it.
    private static final String VISION_MODEL_GROQ = "qwen/qwen3.6-27b";

    // Primary screen reader as of 2026-08-22. Measured head-to-head against
    // qwen3.6-27b on a hard synthetic screen (file tree, two compiler errors,
    // a failing test with expected/actual values, exact line numbers): Gemini
    // read every fact in 1.46s, qwen recognised the problem shape and answered
    // from memory instead of reading the red compile error on screen. Also
    // ~37% fewer prompt tokens and no per-minute ceiling, unlike Groq's free
    // tier. See MAC_CATCHUP.md for the full comparison.
    private static final String VISION_MODEL_GEMINI = "gemini-3.1-flash-lite";

    // The spoken-answer model. Same family as the vision one on purpose: it is
    // the fastest tier Google sells, measured under a second to first token,
    // which is the number that matters when somebody is waiting to speak.
    private static final String ANSWER_MODEL_GEMINI = "gemini-3.1-flash-lite";
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
        //
        // Gemini answers first when its key is present, and Groq behind it.
        //
        // This used to lead with Groq's free tier and it was failing users
        // outright, not slowly: measured on one real session, ten requests in
        // twenty-five minutes produced six rate limits, and once both Groq
        // models were drained the chain ended at an OpenAI account that has
        // been inactive since 2026-08-22 — credits deducted, every provider
        // refused, credits refunded, and the candidate given nothing at all
        // while an interviewer waited. A free tier metered per minute cannot
        // carry a paid product's answer path; it can only stand behind one.
        //
        // Gemini's limits are per-day rather than per-minute and its flash-lite
        // tier is both cheaper per token and faster than what it replaces, so
        // the burst of questions that drained Groq is exactly the shape it
        // handles best. Groq stays as the fallback: still free, still fast,
        // and now only reached when Gemini itself is unavailable.
        final boolean answerOnGemini = geminiApiKey != null && !geminiApiKey.isBlank();

        String endpoint = answerOnGemini ? GEMINI_ENDPOINT       : GROQ_ENDPOINT;
        String apiKey   = answerOnGemini ? geminiApiKey          : groqApiKey;
        String model    = answerOnGemini ? ANSWER_MODEL_GEMINI   : DEFAULT_MODEL;

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("No API key for provider: " + provider);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // Alternate model — used when the primary is rate-limited or unavailable.
        // The SAME aiMessages are reused, so conversation context stays identical.
        //
        // The fallback used to be the other provider, which assumed OpenAI was
        // always there. It was not: a key with billing switched off answers
        // every request with 402, so a Groq rate limit became a hard failure and
        // the candidate read "The AI service is temporarily unavailable" while
        // an interviewer waited.
        //
        // Groq meters tokens per model, not per account. Measured on the live
        // key: draining gpt-oss-20b to 5,185 remaining left gpt-oss-120b
        // untouched at 7,927. So the larger model is a real second budget on
        // the same free tier, and a better answer besides.
        //
        // Falling back within Groq first therefore costs nothing, needs no
        // billing, and turns most rate limits into a slightly slower answer
        // rather than none. OpenAI stays as the third try for whoever has it,
        // and is skipped without a word when the key is missing or inactive.
        String fallbackProvider;
        String fallbackEndpoint;
        String fallbackApiKey;
        String fallbackModel;

        if (answerOnGemini) {
            // Gemini was primary, so the fallback is the free tier behind it.
            fallbackProvider = "groq";
            fallbackEndpoint = GROQ_ENDPOINT;
            fallbackApiKey   = groqApiKey;
            fallbackModel    = DEFAULT_MODEL;
        } else {
            fallbackProvider = "groq";
            fallbackEndpoint = GROQ_ENDPOINT;
            fallbackApiKey   = groqApiKey;
            fallbackModel    = SECOND_CHOICE_MODEL;
        }

        // Third try, only for a key that actually works. Reached when both Groq
        // models are limited, which needs 16,000 tokens inside one minute.
        String lastResortEndpoint = OPENAI_ENDPOINT;
        String lastResortApiKey   = openAiApiKey;
        String lastResortModel    = "gpt-4o";

        // 6. Charge UP FRONT (atomic). Deducting after the AI answered meant a
        //    failed deduction still served a free answer; charging first closes
        //    that hole. If every provider fails below, the charge is refunded.
        // Timed because the client counts this as "the AI thinking".
        long chargeStart = System.currentTimeMillis();
        boolean charged = identity.isGuest()
                ? creditsService.deductGuestCredits(identity.deviceId())
                : creditsService.deductCredits(identity.uid());
        long chargeMs = System.currentTimeMillis() - chargeStart;
        if (chargeMs > 250) System.out.println("[SLOW] credit deduction " + chargeMs + "ms");
        if (!charged) {
            return ResponseEntity.status(402).build(); // Payment Required
        }

        // 7. Stream response
        StreamingResponseBody stream = outputStream -> {
            boolean providerAccepted = false;
            boolean answerDelivered = false;
            try {
                var messages = aiMessages;   // effectively final — captured from above

                long providerStart = System.currentTimeMillis();
                HttpResponse<java.io.InputStream> response = callAiProvider(endpoint, apiKey, model, messages);
                System.out.println("[SLOW] provider " + model + " first byte in "
                        + (System.currentTimeMillis() - providerStart) + "ms");

                // Rate limit / server error from the upstream provider is usually transient —
                // retry once after a short backoff before giving up on this provider.
                // A rate limit is not retried on the same model. A server error is.
                //
                // Sleeping eight seconds and asking the same model again was the
                // worst of both: too long to feel responsive, and far too short
                // to help. The bucket refills at a sixtieth of the limit a
                // second, so a drained one needs fifteen seconds or more, and
                // the retry reliably woke up to the same 429. Measured end to
                // end at about nine seconds before the error appeared — nine
                // seconds of silence in front of an interviewer, which reads as
                // a hang rather than a limit.
                //
                // The second model exists precisely because Groq meters per
                // model, so on a 429 it is already the right next move and there
                // is nothing to wait for. Falling straight through costs one
                // round trip instead of eight seconds plus one.
                //
                // A 5xx is different: it is usually a moment of trouble at the
                // provider, and the same model a second later often works. That
                // keeps its backoff.
                if (response.statusCode() >= 500) {
                    long waitMs = retryDelayMs(response);
                    System.err.println("Provider " + provider + " returned HTTP " + response.statusCode()
                            + ", retrying in " + waitMs + "ms");
                    Thread.sleep(waitMs);
                    response = callAiProvider(endpoint, apiKey, model, messages);
                } else if (response.statusCode() == 429) {
                    System.err.println("Provider " + provider + " rate limited; going straight to "
                            + fallbackModel + " rather than waiting.");
                }

                // Second try: the other Groq model, which has its own token
                // budget. Same messages, so the answer is built from identical
                // context either way.
                if (response.statusCode() != 200 && fallbackApiKey != null && !fallbackApiKey.isBlank()) {
                    System.err.println("Provider " + provider + " returned HTTP " + response.statusCode()
                            + ", falling back to " + fallbackProvider + "/" + fallbackModel);
                    response = callAiProvider(fallbackEndpoint, fallbackApiKey, fallbackModel, messages);
                }

                // Third try: OpenAI, for accounts that have it. Skipped in
                // silence when the key is absent, which is the normal case
                // before anyone has set up billing, and must not be treated as
                // an error worth logging on every request.
                if (response.statusCode() != 200
                        && lastResortApiKey != null && !lastResortApiKey.isBlank()
                        && !lastResortEndpoint.equals(fallbackEndpoint)) {
                    System.err.println("Both Groq models returned HTTP " + response.statusCode()
                            + ", trying openai/" + lastResortModel);
                    response = callAiProvider(lastResortEndpoint, lastResortApiKey, lastResortModel, messages);
                }

                if (response.statusCode() != 200) {
                    System.err.println("AI error: HTTP " + response.statusCode());
                    outputStream.write(response.statusCode() == 429
                            ? rateLimitedEvent(response).getBytes()
                            : friendlyErrorEvent().getBytes());
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
    // ══════════════════════════════════════════════════════════════════════
    // THE SCREENSHOT, SENT BEFORE THE QUESTION
    //
    // Measured on a real capture: 1,483ms from the candidate stopping speaking
    // to the first word appearing, of which the model was 720ms. Most of the
    // rest was the picture going up the wire, and it goes twice — to here, and
    // from here to the model.
    //
    // The app already takes the screenshot before the question is asked, so it
    // can send it then too, while nobody is waiting. What is left on the path
    // when the question finally arrives is an id.
    //
    // Held in memory only, briefly. A screenshot is the most personal thing
    // this product ever handles: it is whatever was on someone's screen. It is
    // never written to disk, never logged, readable only by the identity that
    // sent it, and gone in ninety seconds whether it was used or not.
    // ══════════════════════════════════════════════════════════════════════
    // Three views is a scrolled problem statement. More is a screen recording,
    // and the cost of reading them lands on somebody waiting to speak.
    private static final int    MAX_IMAGES_PER_QUESTION = 3;
    private static final int    SCREEN_CACHE_PER_MINUTE = 40;
    private static final long   STASHED_IMAGE_TTL_MS = 90_000;

    // What the stash may hold, in bytes and per person.
    //
    // It was two hundred images with no size limit beyond the eight megabyte
    // ceiling on a single upload, and no per-user share. Two hundred times
    // eight megabytes is 1.5 GB against a 1.38 GB heap: one signed-in caller
    // could exhaust the heap in about five minutes and take the backend down
    // for everybody. The count was global too, so filling it denied service to
    // every other user long before memory ran out.
    //
    // A real screenshot is around half a megabyte encoded. Two megabytes is
    // already generous for one, three views is a scrolled problem, and sixty
    // megabytes across everyone is a fraction of the heap even when full.
    private static final int    MAX_STASHED_IMAGE_CHARS = 2_000_000;
    private static final int    MAX_STASHED_PER_IDENTITY = 4;
    private static final long   MAX_STASHED_TOTAL_BYTES = 60L * 1024 * 1024;

    private record StashedImage(String owner, String base64, long expiresAt) {}

    private final java.util.Map<String, StashedImage> stashedImages =
            new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/screen-cache")
    public ResponseEntity<?> stashScreenshot(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest request,
            @RequestBody Map<String, Object> payload) {

        RequestIdentity identity = identityResolver.resolve(authHeader, deviceId);
        if (identity == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        // Same ceiling as a real screen request. This costs no credits and calls
        // no model, but it does hold memory, so it is not a free-for-all.
        // Its own allowance, well above the one for a real request.
        //
        // This was sharing the twelve-a-minute ceiling meant for calls that
        // spend credits and reach a model. Sending a screenshot ahead does
        // neither: it is a memory write with a ninety-second life. The app
        // prepares one every couple of seconds while watching a screen, so the
        // shared ceiling rejected most of them, the question fell back to
        // carrying the picture inline, and the whole point of sending it early
        // was lost — measured at 1,461ms to the first word, right back where it
        // started.
        //
        // Forty a minute is more than the app can produce and still small
        // enough to bound the memory, which is what the ceiling is protecting.
        if (!rateLimiter.tryAcquire("screen-cache:ip:" + rateLimiter.clientIp(request),
                                    SCREEN_CACHE_PER_MINUTE * 4, 60_000L)
                || !rateLimiter.tryAcquire("screen-cache:identity:" + identityKey(identity),
                                    SCREEN_CACHE_PER_MINUTE, 60_000L))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();

        String image = text(payload.get("image"), MAX_IMAGE_BASE64_CHARS);
        if (image == null || image.isBlank() || !isBase64(image))
            return ResponseEntity.badRequest().body(Map.of("error", "image missing or not valid base64"));

        // Far larger than any real screenshot. Rejected rather than trimmed,
        // because something sending eight megabytes is not a screen capture.
        if (image.length() > MAX_STASHED_IMAGE_CHARS)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "screenshot too large to hold"));

        sweepStashedImages();

        String owner = identityKey(identity);

        // One caller's share, so nobody can crowd anyone else out. Their own
        // oldest goes first: they are the ones who queued too many, and the
        // newest view is the one their next question needs.
        var mine = stashedImages.entrySet().stream()
                .filter(e -> e.getValue().owner().equals(owner))
                .sorted(java.util.Comparator.comparingLong(e -> e.getValue().expiresAt()))
                .toList();
        for (int i = 0; i <= mine.size() - MAX_STASHED_PER_IDENTITY; i++)
            stashedImages.remove(mine.get(i).getKey());

        // And a ceiling across everyone, measured in bytes rather than count,
        // because count says nothing about memory.
        long held = stashedImages.values().stream()
                .mapToLong(held2 -> held2.base64().length())
                .sum();
        if (held + image.length() > MAX_STASHED_TOTAL_BYTES)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "too many screenshots held right now"));

        String id = java.util.UUID.randomUUID().toString();
        stashedImages.put(id, new StashedImage(identityKey(identity), image,
                System.currentTimeMillis() + STASHED_IMAGE_TTL_MS));

        return ResponseEntity.ok(Map.of("imageId", id, "expiresInMs", STASHED_IMAGE_TTL_MS));
    }

    /**
     * The stashed image, if this caller is the one who stashed it.
     *
     * Taken rather than read: an id is good for one question. That keeps a
     * leaked id worthless a moment later, and stops a stale screen being
     * answered twice.
     */
    private String takeStashedImage(String id, RequestIdentity identity) {
        if (id == null || id.isBlank()) return null;
        sweepStashedImages();

        StashedImage held = stashedImages.get(id);
        if (held == null) return null;

        // Ownership decides, not the id. Ids are guessable in principle and
        // screenshots are not something to hand to whoever asks.
        if (!held.owner().equals(identityKey(identity))) return null;
        if (System.currentTimeMillis() > held.expiresAt()) {
            stashedImages.remove(id);
            return null;
        }

        stashedImages.remove(id);
        return held.base64();
    }

    /** Same shape the rate limiter already uses, so one caller means one caller. */
    private static String identityKey(RequestIdentity identity) {
        return identity.isGuest() ? "guest:" + identity.deviceId() : "user:" + identity.uid();
    }

    /**
     * Every screenshot in the list that this caller stashed and has not used.
     *
     * Silently drops any that expired or were already spent rather than failing
     * the whole request: three views with one missing still answers the
     * question better than refusing to answer at all.
     */
    private List<String> takeStashedImages(Object raw, RequestIdentity identity) {
        var found = new ArrayList<String>();
        if (!(raw instanceof List<?> ids)) return found;

        for (Object id : ids) {
            if (found.size() >= MAX_IMAGES_PER_QUESTION) break;
            if (!(id instanceof String text) || text.isBlank()) continue;
            String image = takeStashedImage(text, identity);
            if (image != null) found.add(image);
        }
        return found;
    }

    private void sweepStashedImages() {
        long now = System.currentTimeMillis();
        stashedImages.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }

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

        // Ids of screenshots already sent, or the bytes inline as before.
        // Older builds send only the bytes and must keep working.
        List<String> stashedImages = takeStashedImages(payload.get("imageIds"), identity);

        String imageId = textOrEmpty(payload.get("imageId"), 64);
        if (stashedImages.isEmpty() && imageId != null && !imageId.isBlank()) {
            String one = takeStashedImage(imageId, identity);
            if (one != null) stashedImages = List.of(one);
        }

        String image = !stashedImages.isEmpty()
                ? stashedImages.get(0)
                : text(payload.get("image"), MAX_IMAGE_BASE64_CHARS);
        String prompt = text(payload.get("prompt"), MAX_SCREEN_PROMPT_CHARS);
        String providerInput = textOrEmpty(payload.get("provider"), 20);
        if (providerInput == null) return rejectScreen("provider field was not usable text");
        final String provider = providerInput.isBlank() ? "groq" : providerInput.toLowerCase();

        if (image == null || image.isBlank()) {
            // An id that resolved to nothing is worth naming: it means the
            // screenshot expired, or was already used, and the app should have
            // sent the bytes instead.
            if (imageId != null && !imageId.isBlank())
                return rejectScreen("imageId was unknown, expired, already used, or not yours");
            return rejectScreen("image missing, blank, not valid base64, or longer than " + MAX_IMAGE_BASE64_CHARS + " chars");
        }
        if (prompt == null || prompt.isBlank())
            return rejectScreen("prompt missing, blank, or longer than " + MAX_SCREEN_PROMPT_CHARS + " chars");
        if (!isBase64(image))
            return rejectScreen("image was not valid base64");
        if (!provider.equals("groq") && !provider.equals("openai"))
            return rejectScreen("provider not on the allow-list");

        // Vision runs on Gemini whatever the caller asks for, same as it ran on
        // OpenAI before this change regardless of the "groq" the desktop app
        // sends — the provider field only gates the allow-list check above, it
        // has not chosen the real route in some time.
        //
        // Gemini first: measured on a hard synthetic screen (file tree, two
        // compiler errors, a failing test with exact expected/actual values)
        // it read every fact in 1.46s where qwen3.6-27b recognised the problem
        // shape and answered from memory instead of reading the compile error
        // on screen. Verified against the real endpoint before this switch,
        // including the detail:high field this file always attaches to
        // image_url — Gemini's OpenAI-compat endpoint ignores it rather than
        // rejecting it, same as Groq does.
        //
        // Groq behind it as the free fallback. OpenAI dropped from the
        // automatic chain on 2026-08-22: the account came back "account is not
        // active, please check your billing" on every model tested, confirmed
        // by calling it directly, not inferred. Re-add a third tier here if
        // that account is ever reactivated — see MAC_CATCHUP.md.
        //
        // Assigned once. The streaming lambda below captures these, so they
        // have to stay effectively final.
        final boolean primaryIsGemini = geminiApiKey != null && !geminiApiKey.isBlank();

        String endpoint = primaryIsGemini ? GEMINI_ENDPOINT     : GROQ_ENDPOINT;
        String apiKey   = primaryIsGemini ? geminiApiKey        : groqApiKey;
        String model    = primaryIsGemini ? VISION_MODEL_GEMINI : VISION_MODEL_GROQ;

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("No vision API key configured for either provider");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        String fallbackProvider = primaryIsGemini ? "groq" : "gemini";
        String fallbackEndpoint = primaryIsGemini ? GROQ_ENDPOINT      : GEMINI_ENDPOINT;
        String fallbackApiKey   = primaryIsGemini ? groqApiKey         : geminiApiKey;
        String fallbackModel    = primaryIsGemini ? VISION_MODEL_GROQ  : VISION_MODEL_GEMINI;

        final String finalImage  = image;
        final List<String> finalImages = stashedImages.isEmpty()
                ? List.of(image)
                : List.copyOf(stashedImages);
        final String finalPrompt = prompt;

        // Charge UP FRONT (atomic) — same contract as /ask: no unpaid answers,
        // and a refund below if every vision provider fails.
        // Timed because the client counts this as "the AI thinking".
        long chargeStart = System.currentTimeMillis();
        boolean charged = identity.isGuest()
                ? creditsService.deductGuestCredits(identity.deviceId())
                : creditsService.deductCredits(identity.uid());
        long chargeMs = System.currentTimeMillis() - chargeStart;
        if (chargeMs > 250) System.out.println("[SLOW] credit deduction " + chargeMs + "ms");
        if (!charged) {
            return ResponseEntity.status(402).build(); // Payment Required
        }

        StreamingResponseBody stream = outputStream -> {
            boolean providerAccepted = false;
            boolean answerDelivered = false;
            try {
                // Reading the screen and writing the code are different jobs.
                //
                // The vision model can do the first and cannot reliably do the
                // second. Asked to fix an LRU Cache it produced, across three
                // attempts, three different implementations, each broken in a
                // new way — one declaring "Node head, tail;" as objects and then
                // using head->nxt on them, which does not compile. It is a 27B
                // model reading pixels; correct C++ for a data structure problem
                // is not what it is for.
                //
                // So the screen is read by the model that can see, and the code
                // is written by the model that can code. gpt-oss-120b never sees
                // the image: it gets a description of the problem and the error,
                // which is all it needs and a fraction of the tokens.
                //
                // It also spreads the load. Groq meters per model, so the two
                // stages draw on separate allowances rather than racing each
                // other for one.
                String screenRead;
                try {
                    screenRead = readScreenForCoding(
                            endpoint, apiKey, model, finalImages, finalPrompt);
                } catch (RateLimitedException limited) {
                    outputStream.write(rateLimitedEvent(limited.response).getBytes());
                    outputStream.flush();
                    return;
                }

                // "none: not a coding screen" is stage one correctly reporting
                // there was nothing to extract — the screen was a browser tab,
                // a dashboard, anything ordinary. Sending that on to stage two
                // forced a coding-interview answer out of a model that never
                // saw the screen either, for a question that was never about
                // code. Falling through to the single-stage path below instead
                // lets the client's own prompt handle it — the one that
                // already knows how to answer or ignore a screen normally.
                boolean noCodingProblem = screenRead != null
                        && screenRead.toLowerCase(java.util.Locale.ROOT).contains("none: not a coding screen");

                // Which of the two paths actually answered is the single most
                // useful thing in this log and was not recorded anywhere: a
                // two-stage answer and a single-stage one look identical from
                // outside, so an answer built by the vision model straight from
                // the image — where the signature rewrite above cannot reach —
                // was indistinguishable from one built by the coding model from
                // rewritten text. Say plainly which ran.
                System.out.println("[SCREEN_PATH] twoStageEligible="
                        + (screenRead != null && !screenRead.isBlank() && !noCodingProblem)
                        + " screenReadNull=" + (screenRead == null)
                        + " noCodingProblem=" + noCodingProblem);

                // The constraints are not on screen, so the problem continues
                // below it — say so instead of solving a problem half of which
                // has not been read.
                //
                // This is decided here rather than asked of the coding model,
                // because asking was measured at roughly half right: given the
                // identical cut-off screen twice, it said "let me scroll" once
                // and confidently wrote a full solution the other time. That is
                // the same failure as the signature bug below — a model asked to
                // apply a rule applies it sometimes — and the same answer works:
                // stage one reports a concrete observation ("CONSTRAINTS: not
                // visible", which it can simply see) and the decision is made in
                // code, where it happens every time or never.
                //
                // Worth being clear about why this matters at all, since the
                // model will nearly always recognise the problem and produce a
                // plausible solution anyway: the hidden part is exactly where
                // an interviewer's version differs from the famous one — a bound
                // that rules out the obvious approach, a follow-up demanding
                // O(1) space. A confident answer to the wrong problem is worse
                // than a three-second pause to scroll.
                if (screenRead != null && !noCodingProblem
                        && screenRead.toLowerCase(java.util.Locale.ROOT)
                                     .contains("constraints: not visible")) {
                    System.out.println("[SCREEN_PATH] constraints off-screen — asking to scroll");
                    String ask = "SAY THIS\nLet me scroll down and read the constraints "
                               + "before I answer.\n\nNEED\nThe constraints section.";
                    var scrollChunk = Map.of("choices",
                            List.of(Map.of("delta", Map.of("content", ask))));
                    outputStream.write(("data: " + mapper.writeValueAsString(scrollChunk) + "\n\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    outputStream.write("data: [DONE]\n\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    outputStream.flush();
                    providerAccepted = true;
                    answerDelivered = true;
                    return;
                }

                if (screenRead != null && !screenRead.isBlank() && !noCodingProblem) {
                    HttpResponse<java.io.InputStream> coded = callAiProvider(
                            GROQ_ENDPOINT, groqApiKey, SECOND_CHOICE_MODEL,
                            codingMessages(screenRead, finalPrompt));

                    if (coded.statusCode() == 200) {
                        providerAccepted = true;
                        answerDelivered = streamCodingAnswerCorrected(coded, outputStream);
                        if (answerDelivered) {
                            System.out.println("[SCREEN_PATH] answered by TWO-STAGE ("
                                    + SECOND_CHOICE_MODEL + ")");
                            return;
                        }
                    }
                    System.err.println("Coding stage unusable (HTTP " + coded.statusCode()
                            + "); falling back to the vision model writing it.");
                }

                System.out.println("[SCREEN_PATH] answering by SINGLE-STAGE vision (" + model
                        + ") — the signature rewrite does not apply on this path");

                List<Map<String, Object>> messages = buildVisionMessages(finalImages, finalPrompt);

                HttpResponse<java.io.InputStream> response = callVisionProvider(endpoint, apiKey, model, messages);

                // Rate limit / server error from the upstream provider is usually transient —
                // retry once after a short backoff before giving up on this provider.
                // Same reasoning as the answer path: a rate limit has nothing to
                // wait for, a server error does.
                if (response.statusCode() >= 500) {
                    long waitMs = retryDelayMs(response);
                    System.err.println("Vision provider " + provider + " returned HTTP "
                            + response.statusCode() + ", retrying in " + waitMs + "ms");
                    Thread.sleep(waitMs);
                    response = callVisionProvider(endpoint, apiKey, model, messages);
                }

                // Too large. Three views of a scrolled problem is roughly a
                // megabyte and a half of base64, and the provider refuses it
                // outright with a 413 — which then fell through to the other
                // provider, whose account was inactive, and the candidate read
                // "temporarily unavailable" with an interviewer waiting.
                //
                // Dropping to the newest view alone is the right retreat. It is
                // the screen they are looking at now, so the answer is about the
                // right thing even when it cannot see everything they scrolled
                // past. Half the context beats none of it.
                if (response.statusCode() == 413 && finalImages.size() > 1) {
                    System.err.println("Vision payload too large with " + finalImages.size()
                            + " views; retrying with the newest one only.");
                    List<Map<String, Object>> justLatest = buildVisionMessages(
                            List.of(finalImages.get(finalImages.size() - 1)), finalPrompt);
                    response = callVisionProvider(endpoint, apiKey, model, justLatest);
                    messages = justLatest;
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
                    outputStream.write(response.statusCode() == 429
                            ? rateLimitedEvent(response).getBytes()
                            : friendlyErrorEvent().getBytes());
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
                    List<Map<String, Object>> plain = buildVisionMessages(finalImages, PLAIN_VISION_PROMPT);
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
        return buildVisionMessages(List.of(base64Image), prompt);
    }

    /**
     * One question, several pictures of the screen it is written on.
     *
     * A coding problem rarely fits on one screen. The candidate scrolls to read
     * it, and a single screenshot then holds either the statement or the
     * constraints but never both, so the answer was built from half a question
     * and there was no way to tell from reading it.
     *
     * The app captures while they scroll, keeps the views that differ, and
     * sends them together. The model is told they are one screen read top to
     * bottom, in order, so it joins them rather than treating them as three
     * unrelated pictures.
     *
     * Oldest first, because that is the order they were scrolled through and
     * the order the problem reads in.
     */
    private List<Map<String, Object>> buildVisionMessages(List<String> base64Images, String prompt) {
        var content = new ArrayList<Map<String, Object>>();

        String preface = base64Images.size() > 1
                ? "The " + base64Images.size() + " images below are one screen, "
                  + "photographed as the user scrolled down it. They are in order, top "
                  + "to bottom, and they overlap. Read them as a single page: the "
                  + "question may begin in the first and finish in the last. Never "
                  + "describe them as separate screens or mention that there is more "
                  + "than one.\n\n" + prompt
                : prompt;

        content.add(Map.of("type", "text", "text", preface));

        for (String image : base64Images) {
            content.add(Map.of("type", "image_url", "image_url",
                    Map.of("url", "data:image/png;base64," + image, "detail", "high")));
        }

        return List.of(Map.of("role", "user", "content", content));
    }

    // ── Helper: call a vision-capable chat-completions endpoint ───────────────
    private HttpResponse<java.io.InputStream> callVisionProvider(
            String endpoint, String apiKey, String model, List<?> messages) throws Exception {

        // Qwen thinks out loud unless told not to, and the whole of it lands in
        // the content: "<think> The user wants me to identify the colour of the
        // provided image. 1. Analyze the image:..." That would stream straight
        // onto the screen in front of an interviewer. Asking for no reasoning
        // returns the answer alone — "blue" rather than a paragraph about how
        // it decided.
        //
        // Only sent to Groq. OpenAI rejects the field, and a rejected request
        // here means a blank screen answer.
        var aiPayload = new java.util.LinkedHashMap<String, Object>();
        aiPayload.put("model",      model);
        aiPayload.put("messages",   messages);
        aiPayload.put("max_tokens", 4096);
        aiPayload.put("stream",     true);
        if (endpoint.equals(GROQ_ENDPOINT)) {
            aiPayload.put("reasoning_effort", "none");
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

    // ── Helper: call an AI provider's chat-completions endpoint with the given messages ──
    private HttpResponse<java.io.InputStream> callAiProvider(
            String endpoint, String apiKey, String model, List<?> messages) throws Exception {

        var aiPayload = new java.util.HashMap<String, Object>(Map.of(
            "model",             model,
            "messages",          messages,
            "temperature",       0.2,
            "max_tokens",        700,
            "stream",            true,
            "top_p",             0.95
        ));

        // Both penalties are OpenAI/Groq-only. Google's OpenAI-compatibility
        // endpoint validates strictly rather than ignoring what it does not
        // know — it answers 400 "Unknown name \"frequency_penalty\": Cannot
        // find field" and the whole answer fails — so they are added only for
        // the providers that accept them. Verified against both endpoints
        // directly before this was wired up.
        if (!endpoint.equals(GEMINI_ENDPOINT)) {
            aiPayload.put("frequency_penalty", 0.3);
            aiPayload.put("presence_penalty",  0.15);
        }

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
     *
     * Only reached for 5xx now. A real Groq 429 carries none of these headers,
     * so on that path this always returned the 1,200ms default and was then
     * floored to the eight second cap — careful parsing that never once ran in
     * the case it was written for, and eight seconds of silence to show for it.
     * Rate limits go straight to the other model instead.
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

    /**
     * Only coding screens are worth two stages.
     *
     * "What is on my screen" and "read this error to me" are answered perfectly
     * well by the model that can see it, and a second call there would be a
     * second charge for nothing.
     */
    private static final String[] CODING_MARKERS = {
        "solve", "code", "implement", "function", "algorithm", "complexity",
        "compile", "error", "test case", "fix", "debug", "leetcode",
    };

    private static boolean looksLikeCoding(String prompt) {
        if (prompt == null) return false;
        String p = prompt.toLowerCase();
        for (String marker : CODING_MARKERS) if (p.contains(marker)) return true;
        return false;
    }

    /**
     * Stage one: what is on the screen, in words, so a text model can work from
     * it. Short on purpose — the description is an input to the next call, not
     * something anybody reads.
     */
    private String readScreenForCoding(String endpoint, String apiKey, String model,
                                       List<String> images, String prompt) {
        if (!looksLikeCoding(prompt)) return null;

        try {
            // The ASKED line used to be requested with nothing to put in it —
            // this method never received the real question, only the fixed
            // template below, so the model filled ASKED in blind or guessed.
            // actuallyAsked() pulls just the spoken words out of the client's
            // much larger instructional prompt; see its own comment for why
            // that distinction matters here specifically.
            //
            // "or say so" on PROBLEM is the other half of the same fix. Without
            // an explicit way to report that there is no coding problem on
            // screen, a screen that genuinely has none — a pricing dashboard,
            // a browser tab, anything ordinary — left the model filling five
            // required headings from nothing to fill them with. Confirmed
            // live: asked what website was open on a page with no code at
            // all, it invented a LeetCode problem to have something to report.
            String extract = """
                Read this screen and report it. Do not solve anything, do not write code.

                The interviewer just asked: %s

                PROBLEM: the exact title and the full statement as written, including
                every example and constraint you can see. Copy the wording; do not
                summarise it. If the screen is not a coding problem, an editor, or an
                error — a browser tab, a dashboard, anything ordinary — write "none:
                not a coding screen" and say plainly what the screen actually is
                instead. Never invent a problem to have one to report.
                LANGUAGE: the language selected in the editor, or "none".
                EXISTING CODE: the code currently in the editor, exactly as written,
                or "none".
                ERROR: any compile error, failed test or red message, with the exact
                line number and text, or "none".
                CONSTRAINTS: the constraints section of the problem, copied as
                written — the bounds like "1 <= n <= 10^5". If no constraints
                section is visible on the screen, write exactly "not visible".
                Report only what is actually rendered: you will recognise most of
                these problems and could recite their usual constraints from
                memory, and doing that here is the one thing that makes this line
                useless. If you cannot see it, it is not visible.
                ASKED: repeat back the interviewer's question above, in one line.

                Plain text under these six headings. Nothing else.
                """.formatted(actuallyAsked(prompt));

            HttpResponse<java.io.InputStream> res = callVisionProvider(
                    endpoint, apiKey, model, buildVisionMessages(images, extract));
            if (res.statusCode() != 200) {
                System.err.println("Screen read failed: HTTP " + res.statusCode());

                // A rate limit is not worth trying around. The caller's fallback
                // is the same model on the same key, so it fails identically,
                // after an eight second retry, and the person waiting has spent
                // sixteen seconds to be told the same thing they would have been
                // told at once. Say so immediately.
                if (res.statusCode() == 429) throw new RateLimitedException(res);
                return null;
            }

            var text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(res.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    // contentToken strips the "data: " prefix itself (same as
                    // its only other caller, streamUnlessRefused, which passes
                    // the raw line). This used to strip it again before the
                    // call, corrupting the JSON on every line and making this
                    // whole two-stage read-then-code pipeline silently return
                    // "" forever — every request fell through to the weaker
                    // single-stage path without ever failing loudly.
                    String token = contentToken(line);
                    if (token != null) text.append(token);
                    if (text.length() > 8_000) break;
                }
            }
            String raw = text.toString().trim();
            String fixed = fixPointerSignatureIfNeeded(raw);

            // Diagnostic, deliberately loud, added after three fixes for the
            // same bug each looked correct in testing and then failed on the
            // user's very next real request. Reasoning about why a real screen
            // behaves differently from a synthetic one, without being able to
            // see either the extracted text or whether the rewrite fired, is
            // what made those three rounds slow: everything downstream of here
            // was inference. This prints the two facts that end the guessing —
            // whether the signature rewrite actually ran, and what stage one
            // actually extracted — and nothing that is not already being sent
            // to the model anyway.
            System.out.println("[SCREEN_READ] pointerFixApplied=" + !fixed.equals(raw)
                    + " chars=" + raw.length());
            System.out.println("[SCREEN_READ] extract: "
                    + raw.replace('\n', '|').substring(0, Math.min(700, raw.length())));

            return fixed;
        } catch (Exception e) {
            System.err.println("Screen read error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Streams the coding answer, correcting the signature in the finished text
     * rather than trusting the model to have written it correctly.
     *
     * Every earlier fix for this bug acted on the model's *input* — clearer
     * instructions, a worked example, and finally rewriting the extracted
     * screen text before the model ever saw it. Logging added after the third
     * one still failed showed why none of them could work:
     * pointerFixApplied=true, answered by TWO-STAGE — the model was handed a
     * corrected signature and wrote the broken one regardless. Its prior on
     * "what this LeetCode function looks like" is simply stronger than the
     * text in front of it, and no amount of input shaping outranks that.
     *
     * So this is the last place the defect can be caught: after the model has
     * finished, before the user sees it. The same mechanical rule as on the
     * input side — a parameter dereferenced with -> must be declared a pointer,
     * which is not a matter of taste in any C-family language — applied to the
     * answer itself.
     *
     * The cost is that the answer arrives at once instead of streaming in.
     * That is a real loss on a path built for speed, and it is accepted here
     * because the alternative is code that does not compile: a fast wrong
     * answer helps nobody mid-interview, and the coding model returns a few
     * hundred tokens in about a second. Refusal detection still happens first,
     * on the same buffered text, so nothing about that behaviour changes.
     */
    private boolean streamCodingAnswerCorrected(HttpResponse<java.io.InputStream> response,
                                                java.io.OutputStream outputStream) throws Exception {
        var whole = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String token = contentToken(line);
                if (token != null) whole.append(token);
            }
        }

        String answer = whole.toString();
        if (answer.isBlank()) return false;
        if (looksLikeRefusal(answer.substring(0, Math.min(REFUSAL_PROBE_CHARS, answer.length()))))
            return false;

        String corrected = fixPointerSignatureIfNeeded(answer);

        // Always report the signature the user will actually paste, corrected or
        // not. Logging only the corrections left the failing case invisible:
        // when a real answer came back with a value-typed signature and no
        // correction line beside it, there was no way to tell whether the
        // rewrite had run and found nothing, or never run at all — and the
        // difference is the whole bug. One line per answer removes that
        // ambiguity permanently.
        String sigBefore = firstSignatureLine(answer);
        String sigAfter = firstSignatureLine(corrected);
        System.out.println("[SCREEN_SIG] before=[" + sigBefore + "] after=[" + sigAfter + "]"
                + " corrected=" + !corrected.equals(answer));

        // A value-typed parameter that is still dereferenced after the rewrite
        // means the rewrite missed a shape it should have caught. That does not
        // compile, so it is a defect and not a curiosity — say so loudly enough
        // that it is found by reading the log rather than by a user pasting it.
        if (sigAfter.matches(".*\\b(\\w+)\\s+\\w+\\s*\\([^)*]*\\).*")
                && corrected.contains("->")) {
            System.out.println("[SCREEN_SIG] WARNING: signature still value-typed after rewrite "
                    + "— this will not compile: " + sigAfter);
        }

        var chunk = Map.of("choices",
                List.of(Map.of("delta", Map.of("content", corrected))));
        outputStream.write(("data: " + mapper.writeValueAsString(chunk) + "\n\n").getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        outputStream.write("data: [DONE]\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outputStream.flush();
        return true;
    }

    // A whole function-signature line: return type, name, parameter list.
    //
    // Deliberately per-line and permissive about the parameter list rather than
    // matching one exact shape. The previous version required the return type
    // and the single parameter type to be the same word, which held for
    // "ListNode insertionSortList(ListNode head)" and quietly missed anything
    // else — a second parameter, a differing return type, a stray qualifier.
    // Whether a parameter needs a pointer is decided below from how the body
    // uses it, which is the actual rule; the pattern's only job is to find the
    // line and split it up.
    //
    // The lookahead on the separator group is load-bearing. Without it the
    // separator may match empty, so a single word splits in two and
    // "while (curr) {" parses as return type "whil", name "e", parameter
    // "curr" — and gets rewritten to "whil e(cur* r)", turning working code
    // into a syntax error. Caught by the control-flow regression tests below
    // rather than in production, which is the only reason this comment is
    // shorter than that bug would have been.
    private static final java.util.regex.Pattern SIGNATURE_LINE =
            java.util.regex.Pattern.compile(
                    "(?m)^([ \\t]*)([A-Za-z_]\\w*)((?=[\\s*])\\s*\\**\\s*)([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)(\\s*\\{?[ \\t]*)$");

    /** One "Type name" parameter inside a parameter list. */
    private static final java.util.regex.Pattern PARAM =
            java.util.regex.Pattern.compile("^\\s*([A-Za-z_]\\w*)((?=[\\s*])\\s*\\**\\s*)([A-Za-z_]\\w*)\\s*$");

    /**
     * Corrects a value-typed self-referential parameter to a pointer, before
     * the code-writing model ever sees it — rather than asking that model to
     * notice and correct it itself.
     *
     * Three attempts at getting gpt-oss-120b to do this by instruction alone
     * are the reason this exists. An abstract rule ("match pointers and
     * objects") was satisfied in prose and skipped in code. A worked example
     * ("this happened, was sent to a real user, and did not compile") passed
     * four isolated trials in a row and then failed on the very next real
     * request, with the model once again stating the fix out loud and not
     * applying it — different session, same exact shape of failure, this
     * time paired with two claims about the code that were not true either
     * ("missing a closing brace" on code that had one). Better instructions
     * were narrowing the failure rate, not closing it, and code that gets
     * pasted into a compiler during a live interview does not get to fail
     * occasionally.
     *
     * So the fix moved from "ask the model to do it" to "make it already
     * true before the model sees it." A parameter used with -> that is not
     * declared as a pointer is not a judgement call — it does not compile in
     * C, C++, Objective-C, or any language that uses -> for member access,
     * so there is no case where leaving it alone is the right call. Detecting
     * that and rewriting the one line is mechanical, not a heuristic, and it
     * runs on stage one's extraction — text nobody sees — so stage two
     * receives an already-correct signature and has nothing left to get
     * wrong here. This does not replace the worked example above; it is the
     * layer behind it for the cases the model still misses on its own.
     */
    private static String fixPointerSignatureIfNeeded(String screenRead) {
        if (screenRead == null || screenRead.isEmpty()) return screenRead;

        java.util.regex.Matcher m = SIGNATURE_LINE.matcher(screenRead);
        StringBuilder out = new StringBuilder();
        int last = 0;

        while (m.find()) {
            String indent = m.group(1);
            String returnType = m.group(2);
            String returnStars = m.group(3);
            String fnName = m.group(4);
            String params = m.group(5);
            String tail = m.group(6);

            // Control-flow keywords read as "Type name(...)" to a pattern this
            // loose — "if (x)", "while (curr)" — and must never be rewritten.
            if (isKeyword(returnType) || isKeyword(fnName)) continue;

            boolean changed = false;
            var rebuiltParams = new StringBuilder();
            boolean anyParamStarred = false;

            String[] pieces = params.split(",", -1);
            for (int i = 0; i < pieces.length; i++) {
                if (i > 0) rebuiltParams.append(", ");
                java.util.regex.Matcher pm = PARAM.matcher(pieces[i]);
                if (!pm.matches()) { rebuiltParams.append(pieces[i].trim()); continue; }

                String pType = pm.group(1);
                String pStars = pm.group(2);
                String pName = pm.group(3);

                // The rule, and the only one applied here: a parameter the body
                // dereferences with -> has to be declared a pointer. That is not
                // style — the code cannot compile otherwise in any language that
                // uses -> for member access — so there is no version of this
                // where leaving it alone is right.
                boolean dereferenced = screenRead.contains(pName + "->");
                boolean hasStar = pStars.contains("*");
                if (dereferenced && !hasStar && !isKeyword(pType)) {
                    rebuiltParams.append(pType).append("* ").append(pName);
                    changed = true;
                    anyParamStarred = true;
                } else {
                    rebuiltParams.append(pType).append(hasStar ? "* " : " ").append(pName);
                }
            }

            // If a parameter became a pointer and the return type is that same
            // type, the return is the same list/tree and needs the star too —
            // this is the half that was most often left behind, producing code
            // that took a pointer and claimed to return a value.
            String newReturnStars = returnStars;
            if (anyParamStarred && !returnStars.contains("*")
                    && params.contains(returnType)) {
                newReturnStars = "* ";
                changed = true;
            } else if (returnStars.contains("*") && !returnStars.endsWith(" ")) {
                newReturnStars = "* ";
            }

            if (!changed) continue;

            out.append(screenRead, last, m.start());
            out.append(indent).append(returnType)
               .append(newReturnStars.isBlank() ? " " : newReturnStars)
               .append(fnName).append("(").append(rebuiltParams).append(")").append(tail);
            last = m.end();
        }

        out.append(screenRead.substring(last));
        return out.toString();
    }

    /** The first line inside a fence that declares a function, for logging. */
    private static String firstSignatureLine(String text) {
        if (text == null) return "";
        java.util.regex.Matcher m = SIGNATURE_LINE.matcher(text);
        while (m.find()) {
            if (isKeyword(m.group(2)) || isKeyword(m.group(4))) continue;
            return m.group().trim();
        }
        return "none";
    }

    /** Words that look like a type to the signature pattern but never are. */
    private static boolean isKeyword(String word) {
        switch (word) {
            case "if": case "while": case "for": case "switch": case "catch":
            case "return": case "else": case "do": case "sizeof": case "public":
            case "private": case "protected": case "class": case "struct":
                return true;
            default:
                return false;
        }
    }

    /**
     * Stage two: the answer, written by a model that is good at code and has
     * never seen the picture.
     */
    /**
     * The words actually spoken, pulled out of the client's much larger
     * prompt-engineering template.
     *
     * originalPrompt is not a question, it is the whole instructional prompt
     * the desktop app builds for the single-stage vision path — hundreds of
     * lines of formatting rules and worked examples, one of them literally
     * "the LeetCode Two Sum page" as a sample of naming things specifically.
     * Forwarding all of that as "what they asked" to a text model that never
     * sees the image handed it no way to tell an instruction from a fact, and
     * it answered as though a real screen had shown a LeetCode Two Sum
     * problem — on a screen that was a pricing dashboard. Confirmed live: a
     * real request answered "I can see that Chrome is open to the LeetCode
     * Two Sum problem page" while the actual screen was aihubmix.com.
     *
     * The client marks the real question with "THE QUESTION:" before it. That
     * only exists on the voice-driven path, though — the Analyze hotkey (F8,
     * no spoken question at all) sends a completely different template, one
     * with its own CAUSE/FIX/SOLUTION worked examples for the model to
     * imitate, and no marker to isolate anything. Originally this fell back
     * to returning that whole template on the reasoning that a client-shaped
     * miss should degrade to the old behaviour, not fail. That was still the
     * bug: the F8 path kept forwarding its full instructional prompt to
     * stage two exactly as before, and stage two answered in CAUSE/FIX
     * headings copied from the example rather than the SAY THIS/DETAIL shape
     * its own system prompt defines — confirmed against a real session,
     * where the same broken function signature was restated as fixed 22
     * times in a row without the code ever actually changing. A model
     * spending its attention on someone else's formatting examples has less
     * of it left for whether the code it just wrote matches the diagnosis it
     * just wrote above it.
     *
     * So the fallback is now a short, generic instruction instead of the raw
     * prompt — there is no real "question" on this path, only "look at the
     * screen", and that is exactly what it says now.
     */
    private static String actuallyAsked(String originalPrompt) {
        if (originalPrompt == null) return "Analyze what is on the screen.";
        int marker = originalPrompt.indexOf("THE QUESTION:");
        if (marker < 0) return "Analyze what is on the screen.";
        String tail = originalPrompt.substring(marker + "THE QUESTION:".length());
        int stop = tail.indexOf("\n\nAnswer in this shape");
        return (stop > 0 ? tail.substring(0, stop) : tail).trim();
    }

    private List<Map<String, Object>> codingMessages(String screenRead, String originalPrompt) {
        String system = """
            You are the candidate in a live coding interview, answering out loud.
            Someone has read the screen for you and written down what is on it.
            Answer from that as if you were looking at it yourself. Never mention
            the description, the screen, or that anything was read to you.

            Answer in this shape and nothing else:

            SAY THIS
            Two to four sentences, first person, ready to say out loud. If there is
            an error, lead with it: what it is, which line, and what fixes it.
            Otherwise lead with the approach and why.

            DETAIL
            The complete solution, inside a fence: ```language on its own line
            before it and ``` after.

            The code must compile as written. It is pasted straight into the editor.
            Give the whole class or function the problem asks for, never a fragment
            and never one method on its own — a method without its class does not
            compile, and a candidate pasting it looks worse than one who wrote
            nothing. Declare every member. Match pointers and objects: a member
            declared Node* is used with ->, one declared Node is used with a dot,
            and mixing them is the most common way this goes wrong. Do not use a
            variable after deleting or erasing it. Keep the exact class and method
            names the problem gives — the identifiers, the same words, never the
            types on them.

            THE SIGNATURE IS PART OF THE CODE. Worked example, because this exact
            case keeps being explained correctly and then not actually fixed:

            EXISTING CODE said: ListNode insertionSortList(ListNode head) {
            if (!head || !head->next) return head; ...
            WRONG — do not do this: write "I'll change the signature to take
            ListNode* head" in SAY THIS, and then DETAIL still opens with
            ListNode insertionSortList(ListNode head) — the exact same line,
            untouched. This happened, was sent to a real user, and did not
            compile, because saying the fix out loud is not the same action as
            writing it.
            RIGHT: DETAIL opens with ListNode* insertionSortList(ListNode* head) —
            both the parameter and the return type carry the asterisk. Every
            other line may stay exactly as it was; this one line must not.

            The general rule behind that example: a parameter or return type
            declared without a pointer while the body dereferences it with ->
            is never really the problem's own starting code, pointer-typed by
            LeetCode's own templates for every linked-list and tree problem —
            it is a mistake, possibly your own from an earlier turn, being read
            back to you as if it were given. When you name that mistake in SAY
            THIS, DETAIL's first line is where it gets fixed, not restated.

            Before you finish: find the line in DETAIL that declares the method
            you are solving. Compare it, character by character, to the line
            you were told is on the screen. If they are identical and you just
            finished explaining what was wrong with it, you have not fixed
            anything — go back and change that line now, before sending
            anything.

            Then one line: Time O(...), space O(...).""";

        return List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content",
                        "What is on the screen:\n\n" + screenRead
                        + "\n\nWhat they asked:\n" + actuallyAsked(originalPrompt)));
    }

    /** Carries the provider's own headers, so the wait can be reported honestly. */
    private static class RateLimitedException extends RuntimeException {
        final transient HttpResponse<?> response;
        RateLimitedException(HttpResponse<?> response) { this.response = response; }
    }

    private String friendlyErrorEvent() throws Exception {
        var chunk = Map.of("error", "The AI service is temporarily unavailable. Please try again.");
        return "data: " + mapper.writeValueAsString(chunk) + "\n\n";
    }

    /**
     * The same event, but saying which wall was hit and for how long.
     *
     * "The AI service is temporarily unavailable" is true and useless. A rate
     * limit is not an outage: nothing is broken, the allowance for this minute
     * is spent, and it returns on its own. Somebody testing needs to know
     * whether to wait or to start debugging, and those are opposite actions —
     * a morning was spent looking for a fault that was never there.
     */
    private String rateLimitedEvent(HttpResponse<?> response) throws Exception {
        // Read the wait from the headers, and refuse to believe a tiny one.
        //
        // The first version printed "try again in about 1 seconds", which is
        // worse than saying nothing: the caller waits a second, fails again,
        // and now distrusts the message as well as the app. It came from the
        // default fallback, because a real 429 does not carry the header the
        // retry logic reads.
        //
        // The bucket refills at a sixtieth of the limit per second, and a
        // screen question needs around 1,800 tokens, so an empty bucket is
        // closer to a quarter of a minute than to one second. Anything under
        // fifteen seconds is treated as no useful guidance rather than as good
        // news.
        long headerMs = Math.max(
                headerDelayMs(response, "x-ratelimit-reset-tokens"),
                headerDelayMs(response, "retry-after"));

        long seconds = headerMs > 0 ? Math.round(headerMs / 1000.0) : 0;
        if (seconds < 15) seconds = 30;   // no usable header: a fair middle estimate

        var chunk = Map.of("error",
                "This minute's AI allowance is used up. Nothing is broken — it refills every "
                + "minute. Wait about " + seconds + " seconds and ask again. Reading a screen costs "
                + "far more than a spoken question, so screen answers run out first.");
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
