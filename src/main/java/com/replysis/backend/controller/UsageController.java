package com.replysis.backend.controller;

import com.replysis.backend.security.IdentityResolverService;
import com.replysis.backend.security.RequestIdentity;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.FirestoreCreditsService;
import com.replysis.backend.service.FirestoreCreditsService.AudioUsage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Where the apps report how long they have been listening.
 *
 * Credits count questions, and Speechmatics charges by the hour of audio, so
 * until this existed the expensive half was simply not measured. Somebody
 * could hold a microphone open all afternoon, ask five questions, and the
 * books would show twenty-five credits spent.
 *
 * Every client reports the same way, so one meter covers the Windows app, the
 * Mac app and the website without any of them agreeing on anything else.
 *
 * Reports arrive while the interview is happening rather than at the end. An
 * app that crashes, a laptop that closes and a connection that drops all look
 * the same from here, and all three would otherwise be free.
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    // A minute of real interviewing cannot arrive more often than once a
    // minute. Generous enough for retries and several devices, tight enough
    // that a loop cannot hammer Firestore.
    private static final int REPORTS_PER_MINUTE = 10;

    @Autowired private IdentityResolverService identityResolver;
    @Autowired private FirestoreCreditsService creditsService;
    @Autowired private SimpleRateLimiter rateLimiter;

    @PostMapping("/listening")
    public ResponseEntity<?> reportListening(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        RequestIdentity identity = identityResolver.resolve(authHeader, deviceId);
        if (identity == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing token"));

        String ip = rateLimiter.clientIp(request);
        if (!rateLimiter.tryAcquire("usage-ip:" + ip, REPORTS_PER_MINUTE * 4, 60_000L)
                || !rateLimiter.tryAcquire("usage-id:" + identityKey(identity), REPORTS_PER_MINUTE, 60_000L)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "Too many usage reports"));
        }

        int minutes = readMinutes(body);

        AudioUsage usage = identity.isGuest()
                ? creditsService.addGuestListeningMinutes(identity.deviceId(), minutes)
                : creditsService.addListeningMinutes(identity.uid(), minutes);

        // The answer carries what is left, so an app can warn before the cap is
        // reached rather than discovering it when transcription stops.
        return ResponseEntity.ok(Map.of(
                "usedMinutes",      usage.usedMinutes,
                "allowanceMinutes", usage.allowanceMinutes,
                "remainingMinutes", usage.isUnlimited ? -1 : usage.remainingMinutes(),
                "isUnlimited",      usage.isUnlimited,
                "plan",             usage.plan));
    }

    /** Current standing without adding anything, for a screen that shows it. */
    @GetMapping("/listening")
    public ResponseEntity<?> getListening(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {

        RequestIdentity identity = identityResolver.resolve(authHeader, deviceId);
        if (identity == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing token"));

        AudioUsage usage = identity.isGuest()
                ? creditsService.addGuestListeningMinutes(identity.deviceId(), 0)
                : creditsService.addListeningMinutes(identity.uid(), 0);

        return ResponseEntity.ok(Map.of(
                "usedMinutes",      usage.usedMinutes,
                "allowanceMinutes", usage.allowanceMinutes,
                "remainingMinutes", usage.isUnlimited ? -1 : usage.remainingMinutes(),
                "isUnlimited",      usage.isUnlimited,
                "plan",             usage.plan));
    }

    /**
     * Anything unreadable counts as one minute rather than zero.
     *
     * A client that reports badly must not end up cheaper than one that
     * reports properly, which is what rounding a broken payload down to
     * nothing would quietly reward.
     */
    private static int readMinutes(Map<String, Object> body) {
        if (body == null) return 1;
        Object raw = body.get("minutes");
        if (raw instanceof Number n) {
            int minutes = n.intValue();
            return Math.max(0, Math.min(120, minutes));
        }
        try { return Math.max(0, Math.min(120, Integer.parseInt(String.valueOf(raw)))); }
        catch (Exception ignored) { return 1; }
    }

    private static String identityKey(RequestIdentity identity) {
        return identity.isGuest() ? "device:" + identity.deviceId() : "uid:" + identity.uid();
    }
}
