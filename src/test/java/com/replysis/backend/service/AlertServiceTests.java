package com.replysis.backend.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner is told once when something breaks, reminded rarely, told once when it is back, and never flooded.
 */
class AlertServiceTests {

    private final List<String> sent = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private AlertService service() {
        AlertService s = AlertService.forTest((to, subject, body) -> { sent.add(subject); return true; }, now::get);
        // The test sender stands in for the mail service, so mark it configured.
        try {
            var f = AlertService.class.getDeclaredField("resendKey");
            f.setAccessible(true);
            f.set(s, "test-key");
        } catch (Exception e) { throw new AssertionError(e); }
        return s;
    }

    @Test
    void aProblemIsAnnouncedOnceNotOnEveryCheck() {
        AlertService s = service();
        for (int i = 0; i < 20; i++) { s.raise("part-answer_model", "Answers is down", "x"); now.addAndGet(5_000); }
        assertEquals(1, sent.size(), "twenty checks in under two minutes is still one message");
    }

    @Test
    void aStillBrokenThingRemindsAfterHalfAnHourNotBefore() {
        AlertService s = service();
        s.raise("k", "down", "x");
        now.addAndGet(29 * 60_000L);
        s.raise("k", "down", "x");
        assertEquals(1, sent.size());
        now.addAndGet(2 * 60_000L);
        s.raise("k", "down", "x");
        assertEquals(2, sent.size(), "31 minutes later it reminds");
    }

    @Test
    void comingBackIsAnnouncedOnlyIfTheProblemWas() {
        AlertService s = service();
        s.recovered("never-raised", "back", "x");
        assertEquals(0, sent.size(), "no message for something that was never reported");

        s.raise("k", "down", "x");
        now.addAndGet(5 * 60_000L);
        s.recovered("k", "back", "x");
        assertEquals(2, sent.size());
        assertEquals("back", sent.get(1));

        s.recovered("k", "back", "x");
        assertEquals(2, sent.size(), "and only once");
    }

    @Test
    void aSecondProblemOfTheSameKindAfterRecoveryIsAnnouncedAgain() {
        AlertService s = service();
        s.raise("k", "down", "x");
        s.recovered("k", "back", "x");
        now.addAndGet(60_000L);
        s.raise("k", "down", "x");
        assertEquals(3, sent.size());
    }

    @Test
    void aSpikeNeedsEnoughOfThemInsideTheWindow() {
        AlertService s = service();
        s.noteFailure("refunds", 3, 10 * 60_000L, "answers failing", "x");
        s.noteFailure("refunds", 3, 10 * 60_000L, "answers failing", "x");
        assertEquals(0, sent.size(), "two is not a spike");
        s.noteFailure("refunds", 3, 10 * 60_000L, "answers failing", "x");
        assertEquals(1, sent.size(), "the third inside ten minutes is");
    }

    @Test
    void failuresSpreadOutOverTimeNeverAddUpToASpike() {
        AlertService s = service();
        for (int i = 0; i < 20; i++) {
            s.noteFailure("refunds", 3, 10 * 60_000L, "answers failing", "x");
            now.addAndGet(6 * 60_000L);
        }
        assertEquals(0, sent.size(), "one every six minutes never has three inside ten");
    }

    @Test
    void aBadNightCannotBuryTheInbox() {
        AlertService s = service();
        for (int i = 0; i < 40; i++) {
            s.raise("part-" + i, "problem " + i, "x");
        }
        assertEquals(AlertService.MAX_EMAILS_PER_HOUR, sent.size(), "capped at twelve an hour");
    }

    @Test
    void withoutAnEmailKeyNothingIsSentAndNothingBreaks() {
        AlertService s = AlertService.forTest((to, subject, body) -> { sent.add(subject); return true; }, now::get);
        assertDoesNotThrow(() -> s.raise("k", "down", "x"));
        assertEquals(0, sent.size());
        assertEquals(Boolean.FALSE, s.sendTest().get("configured"));
    }

    @Test
    void theRecipientIsShownMaskedNeverInFull() {
        assertEquals("kr...@gmail.com", AlertService.mask("krishnapk288@gmail.com"));
        assertEquals("a...@x.com", AlertService.mask("a@x.com"));
        assertEquals("", AlertService.mask(null));
        assertEquals("", AlertService.mask("not an email"));
    }
}
