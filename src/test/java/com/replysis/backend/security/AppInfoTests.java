package com.replysis.backend.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform and version an app reports are labels for the admin page and nothing else. Anything odd is dropped, never stored.
 */
class AppInfoTests {

    @Test
    void windowsAndMacAreRecognisedWhateverTheirSpelling() {
        assertEquals("windows", AppInfo.parse("windows", "1.0.31").platform());
        assertEquals("windows", AppInfo.parse("  Windows ", "1.0.31").platform());
        assertEquals("mac", AppInfo.parse("mac", "1.0.247").platform());
        assertEquals("mac", AppInfo.parse("macOS", "1.0.247").platform());
    }

    @Test
    void theVersionIsKeptWhenItLooksLikeAVersion() {
        assertEquals("1.0.31", AppInfo.parse("windows", "1.0.31").version());
        assertEquals("1.0.247", AppInfo.parse("mac", " 1.0.247 ").version());
    }

    @Test
    void anythingThatIsNotAVersionIsDroppedNotStored() {
        assertEquals("", AppInfo.parse("windows", "<script>alert(1)</script>").version());
        assertEquals("", AppInfo.parse("windows", "x".repeat(200)).version());
        assertEquals("", AppInfo.parse("windows", "1.0 31").version());
        assertEquals("", AppInfo.parse("windows", null).version());
    }

    @Test
    void anUnknownOrMissingPlatformMeansNoLabelAtAll() {
        assertNull(AppInfo.parse(null, "1.0.31"));
        assertNull(AppInfo.parse("", "1.0.31"));
        assertNull(AppInfo.parse("linux", "1.0.31"));
        assertNull(AppInfo.parse("<b>windows</b>", "1.0.31"));
    }

    @Test
    void outsideARequestThereIsNoApp() {
        assertNull(AppInfo.current());
    }
}
