package com.replysis.backend.controller;

import com.replysis.backend.service.FirestoreCreditsService.UserCredits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A screen read ahead calls a paid model, so it only runs for an account that could pay for the answer it prepares. */
class ReadAheadBillingTests {

    @Test
    void anAccountWithNoAnswersLeftGetsNoReadAhead() {
        assertFalse(InterviewController.canPayForScreenAnswer(new UserCredits(0, "free", false, 0), 5));
        assertFalse(InterviewController.canPayForScreenAnswer(new UserCredits(4, "free", false, 0), 5));
    }

    @Test
    void answersLeftBoughtMinutesOrAnUnlimitedPlanAllowIt() {
        assertTrue(InterviewController.canPayForScreenAnswer(new UserCredits(5, "free", false, 0), 5));
        assertTrue(InterviewController.canPayForScreenAnswer(new UserCredits(0, "pro", false, 30), 5));
        assertTrue(InterviewController.canPayForScreenAnswer(new UserCredits(0, "max", true, 0), 5));
    }

    @Test
    void anUnknownBalanceNeverTakesTheFasterAnswerAway() {
        assertTrue(InterviewController.canPayForScreenAnswer(null, 5));
    }
}
