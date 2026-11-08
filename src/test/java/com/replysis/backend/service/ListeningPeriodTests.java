package com.replysis.backend.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The listening counter is zeroed once per passed reset date, not on every report (bug round 50). The website's
 * app/lib/listening-period.ts holds the same rule.
 */
class ListeningPeriodTests {

    private static final Instant RESET = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    void beforeTheResetDateNothingIsZeroed() {
        assertNull(FirestoreCreditsService.listeningPeriod(RESET, null, RESET.minusSeconds(1)));
    }

    @Test
    void theFirstReportAfterTheResetDateZeroesTheCounterOnce() {
        assertEquals(RESET.toEpochMilli(), FirestoreCreditsService.listeningPeriod(RESET, null, RESET.plusSeconds(60)));
    }

    @Test
    void laterReportsForTheSameResetDateKeepCounting() {
        assertNull(FirestoreCreditsService.listeningPeriod(RESET, RESET.toEpochMilli(), RESET.plusSeconds(3600)));
    }

    @Test
    void aNewerResetDateZeroesAgain() {
        Instant next = Instant.parse("2027-01-01T00:00:00Z");
        assertEquals(next.toEpochMilli(), FirestoreCreditsService.listeningPeriod(next, RESET.toEpochMilli(), next.plusSeconds(1)));
    }

    @Test
    void noResetDateNeverZeroes() {
        assertNull(FirestoreCreditsService.listeningPeriod(null, null, Instant.now()));
    }
}
