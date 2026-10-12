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
 * Interview question sets for the website's mock interview, with the provider keys and the credit rules kept here.
 *
 * One endpoint, for a signed-in person only (a Firebase token: the same account and the same credit balance the Windows app
 * uses). It checks that a model is configured BEFORE charging, charges once, and gives the charge back if the questions
 * cannot be produced. Every failure says what actually went wrong and whether the person was charged.
 */
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    /** The website's price list: CREDIT_ACTION_COSTS.question_generation. */
    static final int QUESTION_SET_CREDITS = 5;
    static final int DEFAULT_COUNT = 50;
    private static final int MIN_COUNT = 10;
    private static final int MAX_RESUME_CHARS = 20_000;
    private static final int MAX_JD_CHARS = 8_000;
    private static final int PER_PERSON_PER_MINUTE = 6;
    private static final int PER_ADDRESS_PER_MINUTE = 30;

    @Autowired private FirebaseAuthService firebaseAuthService;
    @Autowired private FirestoreCreditsService creditsService;
    @Autowired private QuestionGenerationService generator;
    @Autowired private SimpleRateLimiter rateLimiter;

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return fail(HttpStatus.UNAUTHORIZED, "not_signed_in", "Please sign in again.", false);

        if (!rateLimiter.tryAcquire("questions:ip:" + rateLimiter.clientIp(request), PER_ADDRESS_PER_MINUTE, 60_000L)
                || !rateLimiter.tryAcquire("questions:user:" + user.uid(), PER_PERSON_PER_MINUTE, 60_000L))
            return fail(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many tries. Please wait a minute and try again.", false);

        String resume = text(body, "resume", MAX_RESUME_CHARS);
        String jd = text(body, "jd", MAX_JD_CHARS);
        String difficulty = text(body, "difficulty", 40);
        if (resume.trim().length() < 20)
            return fail(HttpStatus.BAD_REQUEST, "resume_missing", "Add your resume text first.", false);
        int count = clampCount(body == null ? null : body.get("count"));

        // Nothing is charged for a service that cannot do the job.
        if (!generator.configured())
            return fail(HttpStatus.SERVICE_UNAVAILABLE, "question_service_not_configured",
                    "Question generation is not set up on this server yet. You were not charged.", false);

        UsageEventService.Context usage = UsageEventService.Context.of("question_generation");
        boolean charged = creditsService.deductCredits(user.uid(), QUESTION_SET_CREDITS, usage);
        if (!charged)
            return fail(HttpStatus.PAYMENT_REQUIRED, "insufficient_credits",
                    "You do not have enough answers left to prepare questions.", false);

        try {
            List<String> questions = generator.generate(resume, jd, count, difficulty);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("questions", questions);
            ok.put("count", questions.size());
            return ResponseEntity.ok(ok);
        } catch (GenerationException e) {
            refund(user.uid(), usage);
            System.err.println("[QUESTIONS] generation failed (" + e.kind() + "): " + e.getMessage());
            if (e.kind() == GenerationException.Kind.BAD_OUTPUT)
                return fail(HttpStatus.BAD_GATEWAY, "bad_output",
                        "The question service did not return a usable set this time. You were not charged. Please try again.", true);
            return fail(HttpStatus.BAD_GATEWAY, "provider_unavailable",
                    "The question service could not be reached right now. You were not charged. Please try again in a minute.", true);
        } catch (RuntimeException e) {
            refund(user.uid(), usage);
            System.err.println("[QUESTIONS] unexpected failure: " + e.getClass().getSimpleName());
            return fail(HttpStatus.INTERNAL_SERVER_ERROR, "question_service_error",
                    "Something went wrong preparing your questions. You were not charged. Please try again.", true);
        }
    }

    private void refund(String uid, UsageEventService.Context usage) {
        try {
            creditsService.refundCredits(uid, QUESTION_SET_CREDITS, usage);
        } catch (RuntimeException e) {
            System.err.println("[QUESTIONS] refund failed for a failed generation: " + e.getClass().getSimpleName());
        }
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
