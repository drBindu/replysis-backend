package com.replysis.backend.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A report from a stranger's computer goes into our log, so it must carry nothing personal and cannot forge a line. */
class DiagnosticsControllerTests {

    @Test
    void anEmailAddressIsRemoved() {
        String out = DiagnosticsController.clean("could not save for someone@example.com now", 200);
        assertFalse(out.contains("@example.com"));
        assertTrue(out.contains("<email>"));
    }

    @Test
    void aUserNameInAPathIsRemoved() {
        String out = DiagnosticsController.clean("C:\\Users\\Swathi\\AppData\\Local\\x.json", 200);
        assertFalse(out.contains("Swathi"));
        assertTrue(out.contains("C:\\Users\\<user>"));
    }

    @Test
    void newlinesCannotForgeALogLine() {
        String out = DiagnosticsController.clean("boom\n[CLIENT_ERROR] v=fake\r\nmore", 200);
        assertFalse(out.contains("\n") || out.contains("\r"));
    }

    @Test
    void textIsBounded() {
        assertEquals(10, DiagnosticsController.clean("x".repeat(500), 10).length());
    }

    @Test
    void aNonTextValueIsIgnored() {
        assertEquals("", DiagnosticsController.clean(12345, 10));
        assertEquals("", DiagnosticsController.clean(null, 10));
    }

    @Test
    void expectedStatesDoNotEmailTheOwner() {
        assertFalse(DiagnosticsController.isRealError("Problem.listening-NoCredits"));
        assertFalse(DiagnosticsController.isRealError("Problem.alert-your-free-answers-are-used"));
        assertFalse(DiagnosticsController.isRealError("Problem.stealth-refused-minor"));
        assertTrue(DiagnosticsController.isRealError("Problem.stealth-refused"));
        assertTrue(DiagnosticsController.isRealError("Crash NullReferenceException"));
        assertTrue(DiagnosticsController.isRealError("Problem.listening-UnstableConnection"));
    }

    @Test
    void oneAddressAloneNeverRaisesTheErrorEmail() {
        DiagnosticsController c = new DiagnosticsController();
        long now = 10_000_000L;
        for (int i = 0; i < 10; i++) assertFalse(c.fromSeveralAddresses("1.2.3.4", now + i));
        assertTrue(c.fromSeveralAddresses("5.6.7.8", now + 20));
        assertFalse(new DiagnosticsController().fromSeveralAddresses("9.9.9.9", now), "a fresh window needs two addresses again");
    }
}
