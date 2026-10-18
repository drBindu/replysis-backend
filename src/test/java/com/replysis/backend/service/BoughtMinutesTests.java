package com.replysis.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * India buys interview minutes at Rs 5 a minute (owner, 2026-10-16). Reported listening is taken from those
 * minutes first, and only the rest counts toward the monthly allowance.
 */
class BoughtMinutesTests {

    @Test
    void listeningComesOutOfBoughtMinutesFirst() {
        assertArrayEquals(new long[] { 59, 0 }, FirestoreCreditsService.spendMinutes(60, 1));
    }

    @Test
    void theLastMinuteEmptiesTheBalanceAndTheRestCountsTowardTheMonth() {
        assertArrayEquals(new long[] { 0, 1 }, FirestoreCreditsService.spendMinutes(1, 2));
    }

    @Test
    void withoutBoughtMinutesEverythingCountsTowardTheMonth() {
        assertArrayEquals(new long[] { 0, 3 }, FirestoreCreditsService.spendMinutes(0, 3));
        assertArrayEquals(new long[] { 0, 3 }, FirestoreCreditsService.spendMinutes(-5, 3), "a broken negative balance is none");
    }

    @Test
    void aBalanceCheckSpendsNothing() {
        assertArrayEquals(new long[] { 30, 0 }, FirestoreCreditsService.spendMinutes(30, 0));
    }

    @Test
    void boughtMinutesPayForLiveAnswersAndScreenReadsOnly() {
        assertTrue(FirestoreCreditsService.minutesCover(UsageEventService.Context.tracked(UsageEventService.ACTION_ANSWER, "cerebras", "m")));
        assertTrue(FirestoreCreditsService.minutesCover(UsageEventService.Context.tracked(UsageEventService.ACTION_SCREEN, "gemini", "m")));
    }

    @Test
    void resumeToolsAndMockInterviewsAreAlwaysChargedInAnswers() {
        assertFalse(FirestoreCreditsService.minutesCover(null), "resume tools charge without a context");
        assertFalse(FirestoreCreditsService.minutesCover(UsageEventService.Context.tracked("questions", "gemini", "m")));
    }
}
