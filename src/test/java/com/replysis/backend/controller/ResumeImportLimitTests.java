package com.replysis.backend.controller;

import com.replysis.backend.security.SimpleRateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 59: the free resume import had only per-minute limits, so it could run thousands of model calls a day. */
class ResumeImportLimitTests {

    @Test
    void aDayAllowsTheCeilingAndNoMore() {
        SimpleRateLimiter limiter = new SimpleRateLimiter();
        long day = 24 * 60 * 60_000L;
        for (int i = 0; i < ResumeController.RESUME_IMPORTS_PER_DAY; i++)
            assertTrue(limiter.tryAcquire("resume-import:day:u1", ResumeController.RESUME_IMPORTS_PER_DAY, day));
        assertFalse(limiter.tryAcquire("resume-import:day:u1", ResumeController.RESUME_IMPORTS_PER_DAY, day));
        assertTrue(limiter.tryAcquire("resume-import:day:u2", ResumeController.RESUME_IMPORTS_PER_DAY, day));
    }

    @Test
    void theCeilingStaysSmall() {
        assertTrue(ResumeController.RESUME_IMPORTS_PER_DAY >= 5 && ResumeController.RESUME_IMPORTS_PER_DAY <= 30);
    }
}
