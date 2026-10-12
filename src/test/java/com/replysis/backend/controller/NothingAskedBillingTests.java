package com.replysis.backend.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A screen with nothing on it to answer is shown to the person but is not charged (a real log: the empty desktop cost an answer). */
class NothingAskedBillingTests {

    @Test
    void theMarkerIsRecognisedHoweverItIsSpaced() {
        assertTrue(InterviewController.opensWithNothingAsked("NOTHING ASKED\nThe screen shows the desktop."));
        assertTrue(InterviewController.opensWithNothingAsked("  \n nothing asked: only the desktop is open"));
        assertTrue(InterviewController.opensWithNothingAsked("Nothing Asked"));
    }

    @Test
    void aRealAnswerIsNeverTreatedAsNothingAsked() {
        assertFalse(InterviewController.opensWithNothingAsked("A thread shares memory with its process."));
        assertFalse(InterviewController.opensWithNothingAsked("The question on screen asks about nothing asked before."));
        assertFalse(InterviewController.opensWithNothingAsked(""));
        assertFalse(InterviewController.opensWithNothingAsked(null));
    }
}
