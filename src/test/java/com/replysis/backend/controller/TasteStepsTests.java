package com.replysis.backend.controller;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 59: the phone never claims the free interview, so an account that had not used it got question sets, feedback
 * and model answers free for ever. The free interview now has a size, counted in mayaTasteSteps (the website's
 * app/lib/paid-plan.ts uses the same field and number); a plan that includes the mock interview is unaffected.
 */
class TasteStepsTests {

    private static Map<String, Object> free(int stepsUsed) {
        Map<String, Object> d = new HashMap<>();
        d.put("plan", "free");
        if (stepsUsed > 0) d.put("mayaTasteSteps", stepsUsed);
        return d;
    }

    @Test
    void theFreeInterviewIsFreeUntilItsStepsAreUsed() {
        long now = System.currentTimeMillis();
        assertTrue(QuestionController.mockIncludedFor(free(0), now));
        assertTrue(QuestionController.mockIncludedFor(free(QuestionController.TASTE_STEPS_MAX - 1), now));
        assertFalse(QuestionController.mockIncludedFor(free(QuestionController.TASTE_STEPS_MAX), now));
    }

    @Test
    void aPlanWithTheMockInterviewIgnoresTheCount() {
        long now = System.currentTimeMillis();
        Map<String, Object> max = free(500);
        max.put("plan", "max");
        assertTrue(QuestionController.mockIncludedFor(max, now));
        assertTrue(QuestionController.planIncludesMock(max, now));

        Map<String, Object> practice = free(500);
        practice.put("mockPlan", true);
        assertTrue(QuestionController.mockIncludedFor(practice, now));

        Map<String, Object> internalPro = free(500);
        internalPro.put("plan", "pro");
        internalPro.put("internal", true);
        assertTrue(QuestionController.mockIncludedFor(internalPro, now));
    }

    @Test
    void theFreeInterviewIsNotPartOfThePlan() {
        long now = System.currentTimeMillis();
        assertFalse(QuestionController.planIncludesMock(free(0), now));
        assertFalse(QuestionController.planIncludesMock(null, now));
    }
}
