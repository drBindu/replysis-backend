package com.replysis.backend.controller;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 59: an account at zero could make /ask send up to six million characters to the model, billed even though the
 * charge was then refused. The messages are capped in total, and an account known to have nothing left is refused before
 * any model call.
 */
class AskLimitsTests {

    private static List<Map<String, Object>> messages(int count, int charsEach) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(Map.of("role", i == 0 ? "system" : "user", "content", "x".repeat(charsEach)));
        return list;
    }

    @Test
    void aRealSizedRequestPasses() {
        // A resume at its 30,000 limit, its rules and a long history.
        assertNotNull(InterviewController.sanitizeMessages(messages(1, 45_000)));
        assertNotNull(InterviewController.sanitizeMessages(messages(40, 2_500)));
    }

    @Test
    void manyLargeMessagesTogetherAreRefused() {
        assertNull(InterviewController.sanitizeMessages(messages(100, 60_000)));
        assertNull(InterviewController.sanitizeMessages(messages(3, 60_000)));
        assertTrue(InterviewController.MAX_TOTAL_MESSAGE_CHARS >= 120_000);
    }

    @Test
    void aRefusedChargeIsRememberedBrieflyAndOnlyForThatAccount() {
        InterviewController controller = new InterviewController();
        assertFalse(controller.knownOutOfAnswers("a"));
        controller.rememberOutOfAnswers("a");
        assertTrue(controller.knownOutOfAnswers("a"));
        assertFalse(controller.knownOutOfAnswers("b"));
        assertFalse(controller.knownOutOfAnswers(null));
        assertTrue(InterviewController.OUT_OF_ANSWERS_MS <= 30_000L, "a buyer must not wait long after paying");
    }
}
