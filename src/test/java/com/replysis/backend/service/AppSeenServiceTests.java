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
        Map<String, Object> fields = AppSeenService.presenceFields(new AppInfo("mac", "1.0.248"), false);
        assertEquals("mac", fields.get("lastPlatform"));
        assertEquals(false, fields.get("appListening"));
        assertEquals("1.0.248", fields.get("lastAppVersion"));
        assertTrue(fields.containsKey("lastAppAt"));
        assertTrue(fields.containsKey("lastAppSeenAt"));
        assertFalse(fields.containsKey("lastWebAt"));
    }

    @Test
    void aPingWithNoAppLabelMarksOnlyTheWebsite() {
        Map<String, Object> fields = AppSeenService.presenceFields(null, false);
        assertTrue(fields.containsKey("lastWebAt"));
        assertEquals(1, fields.size());
    }

    @Test
    void aMissingOrBlankPersonIsIgnoredWithoutAnError() {
        AppSeenService service = new AppSeenService();
        assertDoesNotThrow(() -> service.presence(null, new AppInfo("windows", "1.0.31"), false));
        assertDoesNotThrow(() -> service.presence("  ", null, false));
        assertDoesNotThrow(() -> service.presence("someone", null, false));
        assertDoesNotThrow(() -> service.leave(null, null));
        assertDoesNotThrow(() -> service.leave("someone", new AppInfo("mac", "1.0.249")));
    }

    @Test
    void listeningIsRecordedOnTheAppAndNeverOnTheWebsite() {
        assertEquals(true, AppSeenService.presenceFields(new AppInfo("windows", "1.0.31"), true).get("appListening"));
        assertFalse(AppSeenService.presenceFields(null, true).containsKey("appListening"));
    }

    @Test
    void closingMarksOnlyThatSurfaceAsLeftAndStopsListening() {
        Map<String, Object> app = AppSeenService.leaveFields(new AppInfo("mac", "1.0.249"));
        assertTrue(app.containsKey("lastAppLeftAt"));
        assertEquals(false, app.get("appListening"));
        assertFalse(app.containsKey("lastWebLeftAt"));

        Map<String, Object> web = AppSeenService.leaveFields(null);
        assertTrue(web.containsKey("lastWebLeftAt"));
        assertFalse(web.containsKey("lastAppLeftAt"));
    }
}
