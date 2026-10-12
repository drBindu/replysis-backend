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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The model calls behind the website's mock interview: the question set, the coaching feedback on an answer, and a short
 * better-answer script.
 *
 * The website used to do this itself, from its own Next.js route, with its own copy of the provider key. A local site, or any
 * deployment without that key, failed with a message that said nothing. The keys now live in one place, this backend, which also
 * owns the credit balance the Windows app uses, so the website and the app share one account and one set of rules.
 *
 * Main model first, the backup model if it fails. It never invents content: a model that answers badly, or too thinly, is a
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
    private static final int QUESTIONS_MAX_TOKENS = 3500;
    // The main model reasons before it answers and that counts against the limit, so these are generous.
    private static final int FEEDBACK_MAX_TOKENS = 1600;
    private static final int SCRIPT_MAX_TOKENS = 900;

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

    @FunctionalInterface
    private interface Parser<T> { T parse(String raw) throws GenerationException; }

    /**
     * Asks the main model, then the backup. Both attempts together (connect 4 s + 9 s, then connect 4 s + 11 s) settle inside
     * the website's 32 second wait, so the charge is always either kept for a result that arrived or given back before anyone
     * gives up.
     */
    private <T> T run(String system, String user, int maxTokens, Parser<T> parser) throws GenerationException {
        GenerationException last = null;
        if (cerebrasApiKey != null && !cerebrasApiKey.isBlank()) {
            try {
                return parser.parse(call(cerebrasUrl, cerebrasApiKey, CEREBRAS_MODEL, system, user, true, 9, maxTokens));
            } catch (GenerationException e) {
                last = e;
            }
        }
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return parser.parse(call(geminiUrl, geminiApiKey, GEMINI_MODEL, system, user, false, 11, maxTokens));
            } catch (GenerationException e) {
                // keep the more specific reason: bad output from a model that answered beats "could not reach"
                if (last == null || e.kind() == GenerationException.Kind.BAD_OUTPUT) last = e;
            }
        }
        if (last != null) throw last;
        throw new GenerationException(GenerationException.Kind.PROVIDER_UNAVAILABLE, "no question model is configured");
    }

    // ------------------------------------------------------------------ the three jobs

    public List<String> generate(String resume, String jd, int count, String difficulty) throws GenerationException {
        String system = "You are an interview question generator. Return ONLY valid JSON.\nFormat: {\"questions\":[\"question 1\",...,\"question " + count + "\"]}";
        String user = buildUserPrompt(resume, jd, count, difficulty);
        return run(system, user, QUESTIONS_MAX_TOKENS, raw -> parseQuestions(raw, count));
    }

    /** Coaching on one answer: a score out of ten, what worked, what to change, and a better answer built from the resume. */
    public Map<String, Object> feedback(String resume, String jd, String question, String answer) throws GenerationException {
        String system = "You're a practical interview coach.\n"
                + "Return ONLY this JSON: {\"score\":0-10,\"strengths\":[...],\"improvements\":[...],\"betterAnswerExample\":\"...\",\"resume_proof\":\"...\"}\n"
                + "- Score 0-10: clarity, confidence, relevance\n"
                + "- strengths: 2-3 specific things that worked\n"
                + "- improvements: exact wording tips\n"
                + "- betterAnswerExample: SHORT (3-5 sentences), REAL resume facts\n"
                + "- resume_proof: resume details used";
        return run(system, answerPrompt(resume, jd, question, answer), FEEDBACK_MAX_TOKENS, this::parseFeedback);
    }

    /** A short, natural answer in the person's own voice, for when theirs was too short to coach. */
    public Map<String, Object> script(String resume, String jd, String question, String answer) throws GenerationException {
        String system = "You're helping someone practice their interview. Write a natural, conversational answer.\n"
                + "Return ONLY this JSON: {\"betterAnswerExample\":\"...\",\"resume_proof\":\"...\"}\n"
                + "- 3-5 sentences max, SHORT and SPECIFIC\n"
                + "- Use REAL facts from the resume\n"
                + "- Start: \"Yeah so...\", \"So for that...\"\n"
                + "- resume_proof: specific resume detail used";
        return run(system, answerPrompt(resume, jd, question, answer), SCRIPT_MAX_TOKENS, this::parseScript);
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

    static String answerPrompt(String resume, String jd, String question, String answer) {
        String j = jd == null || jd.isBlank() ? "N/A" : jd.trim();
        return "RESUME:\n" + clip(resume == null ? "" : resume.trim(), 6000)
                + "\n\nJD:\n" + clip(j, 2000)
                + "\n\nQ:\n" + clip(question == null ? "" : question.trim(), 1000)
                + "\n\nANSWER:\n" + clip(answer == null ? "" : answer.trim(), 4000);
    }

    private static String clip(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(?:\\d{1,3}[.)]|[-*•])\\s+");

    /** The JSON in a model's answer, whether bare, wrapped in a code fence, or surrounded by a sentence. */
    JsonNode readJson(String raw) throws GenerationException {
        if (raw == null || raw.isBlank())
            throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the model returned nothing");
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int end = text.lastIndexOf("```");
            if (end >= 0) text = text.substring(0, end);
            text = text.trim();
        }
        try {
            return json.readTree(text);
        } catch (Exception e) {
            int a = text.indexOf('{'), b = text.lastIndexOf('}');
            try {
                if (a >= 0 && b > a) return json.readTree(text.substring(a, b + 1));
            } catch (Exception ignored) {
                // fall through
            }
            throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the model's answer was not valid JSON");
        }
    }

    /**
     * Pulls the questions out of whatever the model returned: the requested object, a bare array, or either wrapped in a code
     * fence. Strips numbering, drops duplicates and empty or absurdly long lines, and requires a usable number of questions.
     */
    List<String> parseQuestions(String raw, int wanted) throws GenerationException {
        JsonNode root = readJson(raw);
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

    /** A score between 0 and 10 and something to say about the answer; a bare {} is not feedback. */
    Map<String, Object> parseFeedback(String raw) throws GenerationException {
        JsonNode root = readJson(raw);
        if (!root.isObject())
            throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the feedback was not an object");
        JsonNode s = root.path("score");
        double score;
        if (s.isNumber()) score = s.asDouble();
        else if (s.isTextual()) {
            try { score = Double.parseDouble(s.asText().trim().replaceAll("[^0-9.]+.*$", "")); }
            catch (NumberFormatException e) { throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the feedback had no score"); }
        } else throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the feedback had no score");
        score = Math.max(0, Math.min(10, score));

        List<String> strengths = strings(root.path("strengths"));
        List<String> improvements = strings(root.path("improvements"));
        String better = root.path("betterAnswerExample").asText("").trim();
        if (strengths.isEmpty() && improvements.isEmpty() && better.isEmpty())
            throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the feedback was empty");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", Math.round(score * 10.0) / 10.0);
        out.put("strengths", strengths);
        out.put("improvements", improvements);
        out.put("betterAnswerExample", better);
        out.put("resume_proof", root.path("resume_proof").asText("").trim());
        return out;
    }

    Map<String, Object> parseScript(String raw) throws GenerationException {
        JsonNode root = readJson(raw);
        String better = root.path("betterAnswerExample").asText("").trim();
        if (better.isEmpty())
            throw new GenerationException(GenerationException.Kind.BAD_OUTPUT, "the model wrote no answer");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("betterAnswerExample", better);
        out.put("resume_proof", root.path("resume_proof").asText("").trim());
        return out;
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                String t = n.isTextual() ? n.asText().trim() : "";
                if (!t.isEmpty()) out.add(t);
            }
        } else if (node.isTextual() && !node.asText().isBlank()) {
            out.add(node.asText().trim());
        }
        return out;
    }

    // ------------------------------------------------------------------ the provider call

    private String call(String url, String key, String model, String system, String user, boolean reasoningLow,
                        int timeoutSeconds, int maxTokens) throws GenerationException {
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            body.put("temperature", 0.3);
            body.put("max_tokens", maxTokens);
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
