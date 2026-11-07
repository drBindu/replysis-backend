package com.replysis.backend.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassExpiryServiceTests {

    @Test
    void anEndedTrialKeepsOnlyWhatWasBought() {
        Map<String, Object> u = PassExpiryService.balanceAfterEnd("trial", 500, false);
        assertEquals(500L, u.get("credits"));
        assertTrue(u.containsKey("audioMinutesAllowance"));
    }

    @Test
    void practiceBoughtDuringATrialSurvivesItsEnd() {
        assertTrue(PassExpiryService.balanceAfterEnd("trial", 0, true).isEmpty(), "credits and listening stay as Practice wrote them");
    }

    @Test
    void practiceUnderAPaidMonthTakesOverWhenTheMonthEnds() {
        Map<String, Object> u = PassExpiryService.balanceAfterEnd("pass", 100, true);
        assertEquals(350L, u.get("credits"));
        assertEquals(60, u.get("audioMinutesAllowance"));
        assertEquals(0L, u.get("creditsUsed"), "the Practice month is spent before the packs");
        assertEquals(0L, u.get("audioMinutesUsed"));
    }

    @Test
    void practiceRunsOnItsSubscriptionOrUntilItsEndDate() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        assertTrue(FirestoreCreditsService.practiceActive(true, null, now));
        assertTrue(FirestoreCreditsService.practiceActive(null, "2026-11-03T00:00:00Z", now));
        assertFalse(FirestoreCreditsService.practiceActive(false, "2026-11-01T00:00:00Z", now));
        assertFalse(FirestoreCreditsService.practiceActive(null, "not a date", now));
    }

    @Test
    void aPracticeAccountSpendsItsMonthBeforeItsPacks() {
        assertEquals(500, FirestoreCreditsService.chargeCap("free", true, 120L));
        assertEquals(250, FirestoreCreditsService.chargeCap("free", true, 60L));
        assertEquals(25, FirestoreCreditsService.chargeCap("free", false, null));
        assertEquals(2500, FirestoreCreditsService.chargeCap("pro", true, null), "a paid plan keeps its own size");
    }

    @Test
    void theTrialReminderSkipsUnsubscribedAndTestAccounts() {
        assertTrue(PassExpiryService.mayMarket(null, null));
        assertTrue(PassExpiryService.mayMarket(false, false));
        assertFalse(PassExpiryService.mayMarket(true, null), "pressed unsubscribe in an earlier email");
        assertFalse(PassExpiryService.mayMarket(null, true), "the owner's own test account");
    }
}
