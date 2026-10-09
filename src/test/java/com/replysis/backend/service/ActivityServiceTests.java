package com.replysis.backend.service;

import com.replysis.backend.security.AppInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What lands in the live feed is a short line about what happened, and nothing from the screen itself. */
class ActivityServiceTests {

    @Test
    void aLineCarriesWhatHappenedWhoForAndHowLong() {
        Map<String, Object> doc = ActivityService.fields("preread", "uid-123", false, "screen read ahead", 2244, new AppInfo("mac", "1.0.249"));
        assertEquals("preread", doc.get("kind"));
        assertEquals("uid-123", doc.get("identityId"));
        assertEquals(false, doc.get("guest"));
        assertEquals(2244L, doc.get("ms"));
        assertEquals("mac", doc.get("platform"));
        assertEquals("1.0.249", doc.get("appVersion"));
    }

    @Test
    void theLineIsOneShortPlainSentence() {
        assertEquals("a b", ActivityService.clean("a\r\n\tb"));
        assertEquals(160, ActivityService.clean("x".repeat(500)).length());
        assertEquals("", ActivityService.clean(null));
    }

    @Test
    void noAppLabelMeansNoPlatformFields() {
        Map<String, Object> doc = ActivityService.fields("rejected", "device-9", true, "turned away", -5, null);
        assertFalse(doc.containsKey("platform"));
        assertEquals(0L, doc.get("ms"), "a negative time is stored as zero");
    }

    @Test
    void anUnexpectedlyLongIdIsCut() {
        assertEquals(128, ActivityService.fields("preread", "z".repeat(500), false, "", 1, null).get("identityId").toString().length());
    }

    @Test
    void recordingWithNoDatabaseOrNoPersonIsAQuietNoOp() {
        ActivityService service = new ActivityService();
        assertDoesNotThrow(() -> service.record("preread", "uid", false, "x", 1, null));
        assertDoesNotThrow(() -> service.record("preread", null, false, "x", 1, null));
    }
}
