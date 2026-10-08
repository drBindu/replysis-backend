package com.replysis.backend.service;

import com.replysis.backend.security.AppInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which surface a presence ping marks as open: an app marks the app fields, a call with no app label marks only the website.
 */
class AppSeenServiceTests {

    @Test
    void anAppPingMarksTheAppAndNeverTheWebsite() {
        Map<String, Object> fields = AppSeenService.presenceFields(new AppInfo("mac", "1.0.248"));
        assertEquals("mac", fields.get("lastPlatform"));
        assertEquals("1.0.248", fields.get("lastAppVersion"));
        assertTrue(fields.containsKey("lastAppAt"));
        assertTrue(fields.containsKey("lastAppSeenAt"));
        assertFalse(fields.containsKey("lastWebAt"));
    }

    @Test
    void aPingWithNoAppLabelMarksOnlyTheWebsite() {
        Map<String, Object> fields = AppSeenService.presenceFields(null);
        assertTrue(fields.containsKey("lastWebAt"));
        assertEquals(1, fields.size());
    }

    @Test
    void aMissingOrBlankPersonIsIgnoredWithoutAnError() {
        AppSeenService service = new AppSeenService();
        assertDoesNotThrow(() -> service.presence(null, new AppInfo("windows", "1.0.31")));
        assertDoesNotThrow(() -> service.presence("  ", null));
        assertDoesNotThrow(() -> service.presence("someone", null));
    }
}
