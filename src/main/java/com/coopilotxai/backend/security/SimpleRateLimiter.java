package com.coopilotxai.backend.security;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window in-memory rate limiter.
 *
 * The backend runs as a single container, so a process-local map is a correct,
 * dependency-free store (mirrors the frontend's app/lib/rate-limit.ts). This is
 * defense-in-depth against abuse of endpoints that mint real Speechmatics tokens
 * or create guest-credit documents: an attacker scripting fresh X-Device-Id
 * values gets stopped at the IP level instead of draining quota.
 */
@Service
public class SimpleRateLimiter {

    private static class Bucket {
        int  count;
        long resetAt;
    }

    private final Map<String, Bucket> store = new ConcurrentHashMap<>();
    private volatile long lastSweep = System.currentTimeMillis();

    /**
     * @param key      caller identifier (uid, deviceId, or client IP)
     * @param limit    max requests per window
     * @param windowMs window length in milliseconds
     * @return true when the request is allowed
     */
    public boolean tryAcquire(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        sweep(now);
        Bucket b = store.compute(key, (k, cur) -> {
            if (cur == null || now >= cur.resetAt) {
                Bucket fresh = new Bucket();
                fresh.count   = 1;
                fresh.resetAt = now + windowMs;
                return fresh;
            }
            cur.count++;
            return cur;
        });
        return b.count <= limit;
    }

    // Opportunistic cleanup so the map can't grow unbounded over a long uptime.
    private void sweep(long now) {
        if (now - lastSweep < 60_000) return;
        lastSweep = now;
        store.entrySet().removeIf(e -> now >= e.getValue().resetAt);
    }

    /** Best-effort client IP honoring the nginx X-Forwarded-For header. */
    public static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        return request.getRemoteAddr();
    }
}
