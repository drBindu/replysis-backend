package com.replysis.backend.controller;

import com.google.firebase.auth.FirebaseToken;
import com.replysis.backend.security.AuthUser;
import com.replysis.backend.security.FirebaseAuthService;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.FirestoreCreditsService;
import com.replysis.backend.service.QuestionGenerationService;
import com.replysis.backend.service.QuestionGenerationService.GenerationException;
import com.replysis.backend.service.UsageEventService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The website's mock interview, with the provider keys and the credit rules kept here: the question set, the coaching on an
 * answer, and a short better-answer script.
 *
 * For a signed-in person only (a Firebase token: the same account and the same credit balance the Windows app uses). Each
 * call checks that a model is configured BEFORE charging, charges once, and gives the charge back if the result cannot be
 * produced. Every failure says what actually went wrong and whether the person was charged.
 */
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    /** The website's price list: CREDIT_ACTION_COSTS.question_generation, .mock_feedback, .mock_script. */
    static final int QUESTION_SET_CREDITS = 5;
    static final int FEEDBACK_CREDITS = 5;
    static final int SCRIPT_CREDITS = 5;
    static final int DEFAULT_COUNT = 50;
    private static final int MIN_COUNT = 10;
    private static final int MAX_RESUME_CHARS = 20_000;
    private static final int MAX_JD_CHARS = 8_000;
    private static final int MAX_QUESTION_CHARS = 2_000;
    private static final int MAX_ANSWER_CHARS = 8_000;
    private static final int PER_ADDRESS_PER_MINUTE = 40;

    @Autowired private FirebaseAuthService firebaseAuthService;
    @Autowired private FirestoreCreditsService creditsService;
    @Autowired private QuestionGenerationService generator;
    @Autowired private SimpleRateLimiter rateLimiter;

    /** The work itself, run only after the person has been charged. */
    @FunctionalInterface
    private interface Work { Map<String, Object> run() throws GenerationException; }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        AuthUser user = verifyToken(authHeader);
        if (user == null) return notSignedIn();
        if (!allow(user, request, "generate", 6)) return rateLimited();

        String resume = text(body, "resume", MAX_RESUME_CHARS);
        String jd = text(body, "jd", MAX_JD_CHARS);
        String difficulty = text(body, "difficulty", 40);
        // A resume is optional: without one the questions are written for the role in the job description, or a general role.
        int count = clampCount(body == null ? null : body.get("count"));

        return charged(user, "question_generation", QUESTION_SET_CREDITS, () -> {
            List<String> questions = generator.generate(resume, jd, count, difficulty);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("questions", questions);
            ok.put("count", questions.size());
            return ok;
        });
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> feedback(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        AuthUser user = verifyToken(authHeader);
        if (user == null) return notSignedIn();
        if (!allow(user, request, "feedback", 15)) return rateLimited();

        String resume = text(body, "resume", MAX_RESUME_CHARS);
        String jd = text(body, "jd", MAX_JD_CHARS);
        String question = text(body, "question", MAX_QUESTION_CHARS);
        String answer = text(body, "answer", MAX_ANSWER_CHARS);
        if (question.isBlank() || answer.isBlank())
            return fail(HttpStatus.BAD_REQUEST, "answer_missing", "There is no answer to review yet.", false);

        return charged(user, "mock_feedback", FEEDBACK_CREDITS, () -> generator.feedback(resume, jd, question, answer));
    }

    @PostMapping("/script")
    public ResponseEntity<Map<String, Object>> script(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        AuthUser user = verifyToken(authHeader);
        if (user == null) return notSignedIn();
        if (!allow(user, request, "script", 15)) return rateLimited();

        String resume = text(body, "resume", MAX_RESUME_CHARS);
        String jd = text(body, "jd", MAX_JD_CHARS);
        String question = text(body, "question", MAX_QUESTION_CHARS);
        String answer = text(body, "answer", MAX_ANSWER_CHARS);
        if (question.isBlank())
            return fail(HttpStatus.BAD_REQUEST, "question_missing", "There is no question to answer yet.", false);

        return charged(user, "mock_script", SCRIPT_CREDITS, () -> generator.script(resume, jd, question, answer));
    }

    // ------------------------------------------------------------------ shared steps

    /**
     * Checks a model is configured, charges once, runs the work, and gives the charge back on any failure. The check comes
     * first so nobody is charged for a service that cannot do the job.
     */
    private ResponseEntity<Map<String, Object>> charged(AuthUser user, String action, int credits, Work work) {
        if (!generator.configured())
            return fail(HttpStatus.SERVICE_UNAVAILABLE, "question_service_not_configured",
                    "This is not set up on this server yet. You were not charged.", false);

        UsageEventService.Context usage = UsageEventService.Context.of(action);
        // With the Practice plan, the Maya add-on, or the one free interview, the mock interview uses no answers.
        boolean included = mockIncluded(user.uid());
        if (!included && !creditsService.deductCredits(user.uid(), credits, usage))
            return fail(HttpStatus.PAYMENT_REQUIRED, "insufficient_credits",
                    "You do not have enough answers left for this.", false);

        try {
            return ResponseEntity.ok(work.run());
        } catch (GenerationException e) {
            if (!included) refund(user.uid(), credits, usage);
            System.err.println("[QUESTIONS] " + action + " failed (" + e.kind() + "): " + e.getMessage());
            if (e.kind() == GenerationException.Kind.BAD_OUTPUT)
                return fail(HttpStatus.BAD_GATEWAY, "bad_output",
                        "The service did not return a usable result this time. You were not charged. Please try again.", true);
            return fail(HttpStatus.BAD_GATEWAY, "provider_unavailable",
                    "The service could not be reached right now. You were not charged. Please try again in a minute.", true);
        } catch (RuntimeException e) {
            if (!included) refund(user.uid(), credits, usage);
            System.err.println("[QUESTIONS] " + action + " unexpected failure: " + e.getClass().getSimpleName());
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "question_service_error",
                    "Something went wrong. You were not charged. Please try again.", true);
        }
    }

    /** Reads the person's account for {@link #mockIncludedFor}; a read failure means "charge as usual". */
    private static boolean mockIncluded(String uid) {
        try {
            Map<String, Object> d = com.google.firebase.cloud.FirestoreClient.getFirestore()
                    .collection("users").document(uid).get().get().getData();
            return mockIncludedFor(d, System.currentTimeMillis());
        } catch (Exception e) {
            System.err.println("[QUESTIONS] could not read the plan, charging as usual: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Whether the mock interview's own steps use no answers (owner, 2026-10-26). The same rule as the website's
     * app/lib/paid-plan.ts mockStepsFree: the Practice plan (mockPlan, or mockPlanUntil in the future); a paid plan (not
     * free, not a trial) with the Maya add-on (mayaAddon, or mayaAddonUntil in the future) or an internal account; the one
     * free interview while it is unused, or for half an hour after it starts (mayaFreeUsedAt).
     */
    static boolean mockIncludedFor(Map<String, Object> d, long now) {
        if (d == null) return false;
        if (Boolean.TRUE.equals(d.get("mockPlan")) || future(d.get("mockPlanUntil"), now)) return true;
        Object plan = d.get("plan");
        boolean paid = plan instanceof String p && !p.isBlank() && !"free".equalsIgnoreCase(p) && !"trial".equals(d.get("planSource"));
        // Max includes Maya (2026-10-27), as on the website (data/mayaAddon.ts MAX_INCLUDES_MAYA).
        if (paid && "max".equalsIgnoreCase((String) plan)) return true;
        if (paid && (Boolean.TRUE.equals(d.get("internal")) || Boolean.TRUE.equals(d.get("mayaAddon"))
                || future(d.get("mayaAddonUntil"), now))) return true;
        if (!Boolean.TRUE.equals(d.get("mayaFreeUsed"))) return true;
        Object at = d.get("mayaFreeUsedAt");
        return at instanceof com.google.cloud.Timestamp t && now - t.toDate().getTime() < 30 * 60_000L;
    }

    /** Whether the Practice plan is running for this account (the apps show "Practice" instead of the free plan). */
    public static boolean practiceActive(String uid) {
        try {
            Map<String, Object> d = com.google.firebase.cloud.FirestoreClient.getFirestore()
                    .collection("users").document(uid).get().get().getData();
            long now = System.currentTimeMillis();
            return d != null && (Boolean.TRUE.equals(d.get("mockPlan")) || future(d.get("mockPlanUntil"), now));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean future(Object iso, long now) {
        if (!(iso instanceof String s) || s.isBlank()) return false;
        try { return java.time.Instant.parse(s).toEpochMilli() > now; } catch (Exception e) { return false; }
    }

    private void refund(String uid, int credits, UsageEventService.Context usage) {
        try {
            creditsService.refundCredits(uid, credits, usage);
        } catch (RuntimeException e) {
            System.err.println("[QUESTIONS] refund failed for a failed request: " + e.getClass().getSimpleName());
        }
    }

    private boolean allow(AuthUser user, HttpServletRequest request, String action, int perPersonPerMinute) {
        return rateLimiter.tryAcquire("questions:ip:" + rateLimiter.clientIp(request), PER_ADDRESS_PER_MINUTE, 60_000L)
                && rateLimiter.tryAcquire("questions:" + action + ":user:" + user.uid(), perPersonPerMinute, 60_000L);
    }

    private AuthUser verifyToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            FirebaseToken decoded = firebaseAuthService.verify(authHeader.substring("Bearer ".length()).trim());
            return new AuthUser(decoded.getUid(), decoded.getEmail(), decoded.getName());
        } catch (Exception e) {
            System.err.println("[QUESTIONS] token verification failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> notSignedIn() {
        return fail(HttpStatus.UNAUTHORIZED, "not_signed_in", "Please sign in again.", false);
    }

    private static ResponseEntity<Map<String, Object>> rateLimited() {
        return fail(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many tries. Please wait a minute and try again.", false);
    }

    static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String code, String message, boolean refunded) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", code);
        out.put("message", message);
        out.put("questions", List.of());
        out.put("creditsRefunded", refunded);
        return ResponseEntity.status(status).body(out);
    }

    private static String text(Map<String, Object> body, String key, int max) {
        Object v = body == null ? null : body.get(key);
        if (!(v instanceof String s)) return "";
        s = s.replace("\u0000", "");
        return s.length() > max ? s.substring(0, max) : s;
    }

    static int clampCount(Object raw) {
        int n = DEFAULT_COUNT;
        if (raw instanceof Number num) n = num.intValue();
        else if (raw instanceof String s) {
            try { n = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* default */ }
        }
        return Math.max(MIN_COUNT, Math.min(DEFAULT_COUNT, n));
    }
}
