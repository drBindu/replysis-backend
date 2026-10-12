package com.replysis.backend.service;

import com.replysis.backend.service.QuestionGenerationService.GenerationException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Question sets come from the main model, then the backup, and a thin or broken answer is a failure, never a made-up set. */
class QuestionGenerationServiceTests {

    private HttpServer server;
    private String base;
    private final AtomicInteger cerebrasCalls = new AtomicInteger();
    private final AtomicInteger geminiCalls = new AtomicInteger();
    private final AtomicReference<String> cerebrasAuth = new AtomicReference<>();
    private final AtomicReference<String> cerebrasBody = new AtomicReference<>();
    private volatile int cerebrasStatus = 200;
    private volatile String cerebrasContent;
    private volatile int geminiStatus = 200;
    private volatile String geminiContent;

    @BeforeEach
    void start() throws Exception {
        cerebrasContent = reply(50);
        geminiContent = reply(50);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cerebras", ex -> {
            cerebrasCalls.incrementAndGet();
            cerebrasAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            cerebrasBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, cerebrasStatus, cerebrasContent);
        });
        server.createContext("/gemini", ex -> {
            geminiCalls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            respond(ex, geminiStatus, geminiContent);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String content) throws java.io.IOException {
        String body;
        if (status / 100 == 2) {
            body = "{\"choices\":[{\"message\":{\"content\":" + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(content) + "}}]}";
        } else {
            body = "{\"error\":\"echo of the request that must never be passed on\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** A model answer in the requested shape with n distinct questions. */
    private static String reply(int n) {
        List<String> q = new ArrayList<>();
        for (int i = 1; i <= n; i++) q.add("\"Question number " + i + " about the project you led?\"");
        return "{\"questions\":[" + String.join(",", q) + "]}";
    }

    private QuestionGenerationService service(String cerebrasKey, String geminiKey) {
        QuestionGenerationService s = new QuestionGenerationService();
        s.setKeys(cerebrasKey, geminiKey);
        s.cerebrasUrl = base + "/cerebras";
        s.geminiUrl = base + "/gemini";
        return s;
    }

    private static final String RESUME = "Senior engineer, built payment systems in Java and Kotlin for six years.";

    @Test
    void theMainModelAnswersAndTheBackupIsNeverAsked() throws Exception {
        List<String> out = service("ck", "gk").generate(RESUME, "Backend engineer", 50, "medium");
        assertEquals(50, out.size());
        assertEquals(1, cerebrasCalls.get());
        assertEquals(0, geminiCalls.get());
        assertEquals("Bearer ck", cerebrasAuth.get(), "the server's own key is what goes to the provider");
        assertTrue(cerebrasBody.get().contains("Generate exactly 50 interview questions"));
        assertTrue(cerebrasBody.get().contains("Difficulty: medium"));
    }

    @Test
    void whenTheMainModelFailsTheBackupAnswers() throws Exception {
        cerebrasStatus = 429;
        List<String> out = service("ck", "gk").generate(RESUME, "", 50, "");
        assertEquals(50, out.size());
        assertEquals(1, cerebrasCalls.get());
        assertEquals(1, geminiCalls.get());
    }

    @Test
    void whenTheMainModelGivesAThinSetTheBackupIsTried() throws Exception {
        cerebrasContent = reply(4);
        List<String> out = service("ck", "gk").generate(RESUME, "", 50, "");
        assertEquals(50, out.size());
        assertEquals(1, geminiCalls.get());
    }

    @Test
    void aSingleConfiguredBackupModelIsEnough() throws Exception {
        QuestionGenerationService s = service("", "gk");
        assertTrue(s.configured());
        assertEquals(50, s.generate(RESUME, "", 50, "").size());
        assertEquals(0, cerebrasCalls.get(), "a missing key is never sent as a request");
    }

    @Test
    void withNoKeyAtAllItSaysSoBeforeAnythingIsTried() {
        QuestionGenerationService s = service("", "  ");
        assertFalse(s.configured());
        GenerationException e = assertThrows(GenerationException.class, () -> s.generate(RESUME, "", 50, ""));
        assertEquals(GenerationException.Kind.PROVIDER_UNAVAILABLE, e.kind());
        assertEquals(0, cerebrasCalls.get() + geminiCalls.get());
    }

    @Test
    void bothModelsDownIsReportedAsUnavailableWithoutLeakingTheProvidersBody() {
        cerebrasStatus = 503;
        geminiStatus = 500;
        GenerationException e = assertThrows(GenerationException.class, () -> service("ck", "gk").generate(RESUME, "", 50, ""));
        assertEquals(GenerationException.Kind.PROVIDER_UNAVAILABLE, e.kind());
        assertFalse(e.getMessage().contains("echo of the request"), "the provider's body is not passed on");
        assertFalse(e.getMessage().contains("ck") && e.getMessage().contains("Bearer"));
    }

    @Test
    void anUnreachableProviderIsReportedAsUnavailable() {
        QuestionGenerationService s = service("ck", "");
        s.cerebrasUrl = "http://127.0.0.1:1/never";
        GenerationException e = assertThrows(GenerationException.class, () -> s.generate(RESUME, "", 50, ""));
        assertEquals(GenerationException.Kind.PROVIDER_UNAVAILABLE, e.kind());
    }

    @Test
    void anAnswerThatIsNotAQuestionSetIsBadOutput() {
        cerebrasContent = "Sure! Here are some questions you might like.";
        geminiContent = "I cannot help with that.";
        GenerationException e = assertThrows(GenerationException.class, () -> service("ck", "gk").generate(RESUME, "", 50, ""));
        assertEquals(GenerationException.Kind.BAD_OUTPUT, e.kind());
    }

    // ------------------------------------------------------------------ feedback and script

    private static final String GOOD_FEEDBACK =
            "{\"score\":8,\"strengths\":[\"Concrete example\",\"Good result\"],\"improvements\":[\"Lead with the outcome\"],"
                    + "\"betterAnswerExample\":\"So for that, I traced a race in our retry queue and fixed it.\",\"resume_proof\":\"retry queue\"}";

    @Test
    void feedbackComesBackWithAScoreAndABetterAnswer() throws Exception {
        cerebrasContent = GOOD_FEEDBACK;
        java.util.Map<String, Object> f = service("ck", "gk").feedback(RESUME, "Backend", "Tell me about a bug.", "I fixed a race condition in our queue.");
        assertEquals(8.0, ((Number) f.get("score")).doubleValue());
        assertEquals(2, ((List<?>) f.get("strengths")).size());
        assertTrue(((String) f.get("betterAnswerExample")).startsWith("So for that"));
        assertEquals(0, geminiCalls.get());
        assertTrue(cerebrasBody.get().contains("practical interview coach"));
        assertTrue(cerebrasBody.get().contains("I fixed a race condition in our queue."));
    }

    @Test
    void aScoreOutsideZeroToTenIsClamped() throws Exception {
        cerebrasContent = "{\"score\":42,\"strengths\":[\"x\"],\"improvements\":[],\"betterAnswerExample\":\"y\"}";
        assertEquals(10.0, ((Number) service("ck", "").feedback(RESUME, "", "q", "a answer here").get("score")).doubleValue());
    }

    @Test
    void feedbackWithNoScoreOrNothingToSayFallsToTheBackup() throws Exception {
        cerebrasContent = "{\"strengths\":[],\"improvements\":[],\"betterAnswerExample\":\"\"}";
        geminiContent = GOOD_FEEDBACK;
        java.util.Map<String, Object> f = service("ck", "gk").feedback(RESUME, "", "q", "an answer long enough");
        assertEquals(8.0, ((Number) f.get("score")).doubleValue());
        assertEquals(1, geminiCalls.get());
    }

    @Test
    void feedbackThatNeverComesIsBadOutputOrUnavailable() {
        cerebrasContent = "{}";
        geminiStatus = 500;
        GenerationException e = assertThrows(GenerationException.class,
                () -> service("ck", "gk").feedback(RESUME, "", "q", "an answer long enough"));
        assertEquals(GenerationException.Kind.BAD_OUTPUT, e.kind());
    }

    @Test
    void withNoResumeTheModelIsToldNotToInventOne() throws Exception {
        cerebrasContent = GOOD_FEEDBACK;
        service("ck", "").feedback("", "Backend engineer", "Why this role?", "Because I like building reliable systems.");
        assertTrue(cerebrasBody.get().contains("None provided."));
        assertTrue(cerebrasBody.get().contains("do NOT invent employers"));
    }

    @Test
    void questionsWithNoResumeAreAskedForTheRole() throws Exception {
        service("ck", "").generate("", "Backend engineer at a bank", 50, "");
        assertTrue(cerebrasBody.get().contains("None provided."));
        assertTrue(cerebrasBody.get().contains("Backend engineer at a bank"));
    }

    @Test
    void aScriptIsTheBetterAnswerAndItsProof() throws Exception {
        cerebrasContent = "{\"betterAnswerExample\":\"Yeah so, I led the payments migration.\",\"resume_proof\":\"payments migration\"}";
        java.util.Map<String, Object> s = service("ck", "").script(RESUME, "", "Tell me about yourself.", "um");
        assertEquals("Yeah so, I led the payments migration.", s.get("betterAnswerExample"));
        assertEquals("payments migration", s.get("resume_proof"));
    }

    @Test
    void aScriptWithNoAnswerTextIsBadOutput() {
        cerebrasContent = "{\"betterAnswerExample\":\"  \"}";
        geminiContent = "{}";
        GenerationException e = assertThrows(GenerationException.class, () -> service("ck", "gk").script(RESUME, "", "q", "a"));
        assertEquals(GenerationException.Kind.BAD_OUTPUT, e.kind());
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void aCodeFencedAnswerIsAccepted() throws Exception {
        List<String> out = service("ck", "").parseQuestions("```json\n" + reply(12) + "\n```", 50);
        assertEquals(12, out.size());
    }

    @Test
    void aBareArrayIsAccepted() throws Exception {
        List<String> q = new ArrayList<>();
        for (int i = 0; i < 11; i++) q.add("\"Why did you pick design " + i + "?\"");
        assertEquals(11, service("ck", "").parseQuestions("[" + String.join(",", q) + "]", 50).size());
    }

    @Test
    void numberingIsStrippedAndDuplicatesAndBlanksAreDropped() throws Exception {
        List<String> q = new ArrayList<>();
        for (int i = 1; i <= 12; i++) q.add("\"" + i + ". How did you handle incident " + i + "?\"");
        q.add("\"1. How did you handle incident 1?\"");
        q.add("\"\"");
        List<String> out = service("ck", "").parseQuestions("{\"questions\":[" + String.join(",", q) + "]}", 50);
        assertEquals(12, out.size());
        assertEquals("How did you handle incident 1?", out.get(0));
    }

    @Test
    void moreThanWantedIsCutToWhatWasAskedFor() throws Exception {
        assertEquals(20, service("ck", "").parseQuestions(reply(30), 20).size());
    }

    @Test
    void tooFewQuestionsIsBadOutput() {
        GenerationException e = assertThrows(GenerationException.class, () -> service("ck", "").parseQuestions(reply(3), 50));
        assertEquals(GenerationException.Kind.BAD_OUTPUT, e.kind());
    }

    @Test
    void emptyAnswerIsBadOutput() {
        assertThrows(GenerationException.class, () -> service("ck", "").parseQuestions("   ", 50));
        assertThrows(GenerationException.class, () -> service("ck", "").parseQuestions(null, 50));
    }
}
