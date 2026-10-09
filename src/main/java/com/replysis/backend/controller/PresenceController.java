package com.replysis.backend.controller;

import com.replysis.backend.security.AppInfo;
import com.replysis.backend.security.IdentityResolverService;
import com.replysis.backend.security.RequestIdentity;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.AppSeenService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * "I am open right now." The desktop apps and the website tab ping this once a minute while they are in front of somebody.
 *
 * It exists so the admin page can say which one is open. Every surface already writes lastActive, which says somebody is here but
 * not where, and the rules that guard a person's own document only let a browser or an app write a fixed list of fields. This is
 * the server writing the extra fact. The label it records comes from the X-App-Platform header an app sends; a call without one is
 * the website. Nothing is decided from it: it never grants, charges or limits anything.
 */
@RestController
@RequestMapping("/api/v1/presence")
public class PresenceController {

    // One a minute is the design; this leaves room for retries and a few windows and tabs, and no more.
    private static final int PINGS_PER_MINUTE = 12;

    @Autowired private IdentityResolverService identityResolver;
    @Autowired private SimpleRateLimiter rateLimiter;
    @Autowired private AppSeenService appSeen;

    /** "I am open" (and "I am listening" with ?listening=1, sent the moment a session starts or stops and on every ping after). */
    @PostMapping
    public ResponseEntity<?> ping(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "listening", required = false) String listening,
            HttpServletRequest request) {

        // Only a signed-in person has a presence worth showing. A guest device is not one.
        RequestIdentity identity = identityResolver.resolve(authHeader, null);
        if (identity == null || identity.uid() == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in first"));

        if (!rateLimiter.tryAcquire("presence-id:" + identity.uid(), PINGS_PER_MINUTE, 60_000L))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").build();

        AppInfo app = AppInfo.parse(request.getHeader("X-App-Platform"), request.getHeader("X-App-Version"));
        appSeen.presence(identity.uid(), app, "1".equals(listening));
        return ResponseEntity.noContent().build();
    }

    /** "I closed." Sent as an app quits or a tab closes, so the admin page can show it gone at once. */
    @DeleteMapping
    public ResponseEntity<?> leave(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {

        RequestIdentity identity = identityResolver.resolve(authHeader, null);
        if (identity == null || identity.uid() == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in first"));

        if (!rateLimiter.tryAcquire("presence-id:" + identity.uid(), PINGS_PER_MINUTE, 60_000L))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").build();

        appSeen.leave(identity.uid(), AppInfo.parse(request.getHeader("X-App-Platform"), request.getHeader("X-App-Version")));
        return ResponseEntity.noContent().build();
    }
}
