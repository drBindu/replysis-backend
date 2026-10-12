package com.replysis.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the set of interview questions for the website's mock interview.
 *
 * The website used to do this itself, from its own Next.js route, with its own copy of the provider key. A local site, or any
 * deployment without that key, failed with a message that said nothing. The keys now live in one place, this backend, which also
 * owns the credit balance the Windows app uses, so the website and the app share one account and one set of rules.
 *
 * Main model first, the backup model if it fails. It never invents questions: a model that answers badly, or too thinly, is a
 * failure, and the caller refunds.
 */
@Service
public class QuestionGenerationService {

    /** What a failed generation says, so the caller can tell the person the truth. */
    public static final class GenerationException extends Exception {
        public enum Kind { PROVIDER_UNAVAILABLE, BAD_OUTPUT }
        private final Kind kind;
        public GenerationException(Kind kind, String message) { super(message); this.kind = kind; }
        public Kind kind() { return kind; }
    }

    public static final int MIN_USABLE_QUESTIONS = 10;
    private static final int MAX_QUESTION_CHARS = 400;

    @Value("${cerebras.api.key:}") private String cerebrasApiKey;
    @Value("${gemini.api.key:}")   private String geminiApiKey;

    // Overridable so a test can point them at a local stand-in.
    String cerebrasUrl = "https://api.cerebras.ai/v1/chat/completions";
    String geminiUrl   = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private static final String CEREBRAS_MODEL = "gpt-oss-120b";
    private static final String GEMINI_MODEL   = "gemini-3.5-flash-lite";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** For tests: keys without Spring. */
    void setKeys(String cerebras, String gemini) { this.cerebrasApiKey = cerebras; this.geminiApiKey = gemini; }

    /** Whether any provider is set up at all. Asked before charging anybody. */
    public boolean configured() {
        return (cerebrasApiKey != null && !cerebrasApiKey.isBlank()) || (geminiApiKey != null && !geminiApiKey.isBlank());
    }

    public List<String> generate(String resume, String jd, int count, String difficulty) throws GenerationException {
        String system = "You are an interview question generator. Return ONLY valid JSON.\nFormat: {\"questions\":[\"question 1\",...,\"question " + count + "\"]}";
        String user = buildUserPrompt(resume, jd, count, difficulty);

        // Both attempts together (connect 4 s + 9 s, then connect 4 s + 11 s) settle inside the website's 32 second wait, so
        // the charge is always either kept for a set that arrived or given back before anyone gives up.
        GenerationException last = null;
        if (cerebrasApiKey != null && !cerebrasApiKey.isBlank()) {
            try {
                return parseQuestions(call(cerebrasUrl, cerebrasApiKey, CEREBRAS_MODEL, system, user, true, 9), count);
            } catch (GenerationException e) {
                last = e;
            }
        }
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return parseQuestions(call(geminiUrl, geminiApiKey, GEMINI_MODEL, system, user, false, 11), count);
            } catch (GenerationException e) {
                // keep the more specific reason: bad output from a model that answered beats "could not reach"
                if (last == null || e.kind() == GenerationException.Kind.BAD_OUTPUT) last = e;
            }
        }
        if (last != null) throw last;
        throw new GenerationException(GenerationException.Kind.PROVIDER_UNAVAILABLE, "no question model is configured");
    }

    // ------------------------------------------------------------------ prompt and parsing

    static String buildUserPrompt(String resume, String jd, int count, String difficulty) {
        String level = difficulty == null || difficulty.isBlank() ? "" : "\nDifficulty: " + difficulty.trim().replaceAll("[\\r\\n]+", " ");
        String r = resume == null ? "" : resume.trim();
        String j = jd == null || jd.isBlank() ? "Software Engineer role" : jd.trim();
        return "Generate exactly " + count + " interview questions.\n\nRESUME:\n" + clip(r, 2500)
                + "\n\nJOB DESCRIPTION:\n" + clip(j, 800) + level
                + "\n\nRules:\n- Mix technical, behavioral, situational\n- No \"Tell me about yourself\"\n- No numbering inside strings\n- Return exactly "
                + count + " questions";
    }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(?:\\d{1,3}[.)]|[-*•])\\s+");

    /**
     * Pulls the questions out of whatever the model returned: the requested object, a bare array, or either wrapped in a code
     * fence. Strips numbering, drops duplicates and empty or absurdly long lines, and requires a usable number of questions.
     */
    List<String> parseQuestions(String raw, int wanted) throws GenerationException {
        if (raw == null || raw.isBlank())
            throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the model returned nothing");
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int end = text.lastIndexOf("```");
            if (end >= 0) text = text.substring(0, end);
            text = text.trim();
        }
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (Exception e) {
            int a = text.indexOf('{'), b = text.lastIndexOf('}');
            try {
                root = (a >= 0 && b > a) ? json.readTree(text.substring(a, b + 1)) : null;
            } catch (Exception ignored) {
                root = null;
            }
            if (root == null)
                throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the model's answer was not valid JSON");
        }
        JsonNode list = root.isArray() ? root : root.path("questions");
        if (!list.isArray())
            throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the model's answer had no list of questions");

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JsonNode n : list) {
            String q = n.isTextual() ? n.asText() : n.path("question").asText("");
            q = LEADING_NUMBER.matcher(q == null ? "" : q).replaceFirst("").trim();
            if (q.isEmpty() || q.length() > MAX_QUESTION_CHARS) continue;
            out.add(q);
        }
        if (out.size() < MIN_USABLE_QUESTIONS)
            throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "only " + out.size() + " usable questions came back");
        List<String> result = new ArrayList<>(out);
        return result.size() > wanted ? new ArrayList<>(result.subList(0, wanted)) : result;
    }

    // ------------------------------------------------------------------ the provider call

    private String call(String url, String key, String model, String system, String user, boolean reasoningLow, int timeoutSeconds)
            throws GenerationException {
        try {
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            body.put("temperature", 0.3);
            body.put("max_tokens", 3500);
            if (reasoningLow) body.put("reasoning_effort", "low");

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> reply = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() / 100 != 2) {
                // The status is kept; the body is not, because it can echo the request.
                throw new GenerationException(GenerationException.Kind.PROVIDER_UNAVAILABLE, "the model service answered HTTP " + reply.statusCode());
            }
            JsonNode content = json.readTree(reply.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual())
                throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the model's reply had no text");
            return content.asText();
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationException(GenerationException.Kind.PROVIDER_UNAVAILABLE, "the model service could not be reached");
        }
    }
}
