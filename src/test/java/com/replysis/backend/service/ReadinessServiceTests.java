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
}
