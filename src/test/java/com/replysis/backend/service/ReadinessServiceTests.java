package com.replysis.backend.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Before the first keep-warm round finishes, every part reads as starting, never as awake: nothing has been checked yet. */
class ReadinessServiceTests {

    @Test
    @SuppressWarnings("unchecked")
    void everyPartIsStartingUntilItHasBeenChecked() {
        Map<String, Object> snapshot = new ReadinessService().snapshot();
        Map<String, Object> systems = (Map<String, Object>) snapshot.get("systems");

        assertEquals(6, systems.size());
        for (String name : new String[]{"answer_model", "screen_model", "database", "speech", "speech_backup", "website"}) {
            assertEquals("starting", ((Map<String, Object>) systems.get(name)).get("state"), name);
        }
        assertEquals(-1L, snapshot.get("roundAgeMs"), "no round has finished yet");
        assertTrue(((Number) snapshot.get("uptimeSeconds")).longValue() >= 0);
    }

    @Test
    void oneMissedCheckIsSlowAndTwoInARowAreDown() {
        var missed = new ReadinessService.Probe(false, 9000, "no answer in 9 s", 0);
        assertEquals("slow", ReadinessService.stateOf(missed, 1), "one hiccup is not an outage");
        assertEquals("down", ReadinessService.stateOf(missed, 2), "two in a row is");
        assertEquals("down", ReadinessService.stateOf(missed, 7));
    }

    @Test
    void aPassingCheckIsAwakeUnlessItWasSlow() {
        assertEquals("awake", ReadinessService.stateOf(new ReadinessService.Probe(true, 120, "", 0), 0));
        assertEquals("slow", ReadinessService.stateOf(new ReadinessService.Probe(true, 3000, "", 0), 0));
    }

    @Test
    void aMissOnTheMainAnswerModelIsCoveredWhenTheBackupAnswers() {
        var main = new ReadinessService.Probe(false, 9000, "no answer in 9 s", 0);
        var covered = ReadinessService.coverByBackup(main, 800, 1);
        assertTrue(covered.ok(), "an answer could still be produced");
        assertEquals("slow", ReadinessService.stateOf(covered, 0), "shown as covered and slow, not red");
        assertTrue(covered.note().contains("backup"));
    }

    @Test
    void whenTheBackupFailsToTheAnswerPathIsDown() {
        var main = new ReadinessService.Probe(false, 9000, "no answer in 9 s", 0);
        assertSame(main, ReadinessService.coverByBackup(main, -1, 1));
        assertSame(main, ReadinessService.coverByBackup(main, -2, 1), "a backup that is not set up covers nothing");
        assertEquals("down", ReadinessService.stateOf(ReadinessService.coverByBackup(main, -1, 1), 2));
    }

    @Test
    void aHealthyMainModelNeedsNoBackup() {
        var main = new ReadinessService.Probe(true, 120, "", 0);
        assertSame(main, ReadinessService.coverByBackup(main, 5000, 1));
    }
}
