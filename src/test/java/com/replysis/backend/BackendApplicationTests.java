package com.replysis.backend;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.replysis.backend.security.SimpleRateLimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendApplicationTests {

    @Test
    void rateLimiterBlocksRequestsAfterTheConfiguredLimit() {
        SimpleRateLimiter limiter = new SimpleRateLimiter();

        assertTrue(limiter.tryAcquire("device-123", 2, 60_000));
        assertTrue(limiter.tryAcquire("device-123", 2, 60_000));
        assertFalse(limiter.tryAcquire("device-123", 2, 60_000));
    }

    @Test
    void rateLimiterKeepsIndependentCallersSeparate() {
        SimpleRateLimiter limiter = new SimpleRateLimiter();

        assertTrue(limiter.tryAcquire("device-a", 1, 60_000));
        assertTrue(limiter.tryAcquire("device-b", 1, 60_000));
    }

    @Test
    void untrustedForwardedHeadersCannotSpoofClientIp() {
        SimpleRateLimiter limiter = new SimpleRateLimiter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.50");

        assertEquals("203.0.113.10", limiter.clientIp(request));
    }
}
