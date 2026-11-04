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

    @Test
    void freeNothingAskedReadsAreCappedPerPersonPerHour() {
        long t = 1_000L * 3_600_000L;
        for (int i = 0; i < InterviewController.NOTHING_ASKED_FREE_PER_HOUR; i++)
            assertTrue(InterviewController.nothingAskedFreeAllowed("u:cap-test", t));
        assertFalse(InterviewController.nothingAskedFreeAllowed("u:cap-test", t));
        assertTrue(InterviewController.nothingAskedFreeAllowed("u:cap-test", t + 3_600_000L), "a new hour starts again");
        assertTrue(InterviewController.nothingAskedFreeAllowed("u:someone-else", t));
    }

    @Test
    void aPersonLeavingIsToldApartFromAProviderFailure() {
        class ClientAbortException extends java.io.IOException {}
        assertTrue(InterviewController.clientLeft(new RuntimeException(new ClientAbortException())));
        assertFalse(InterviewController.clientLeft(new java.io.IOException("provider stream reset")));
        assertFalse(InterviewController.clientLeft(new java.net.http.HttpTimeoutException("timed out")));
    }
}
