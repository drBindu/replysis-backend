package com.replysis.backend.controller;

import com.replysis.backend.security.IdentityResolverService;
import com.replysis.backend.security.RequestIdentity;
import com.replysis.backend.service.CompanionHub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /api/v1/companion/stream: the phone's Companion screen. A live stream of the answers this account's laptop app is
 * getting, starting with the last few (see CompanionHub). Signed-in accounts only.
 */
@RestController
@RequestMapping("/api/v1/companion")
public class CompanionController {

    @Autowired private IdentityResolverService identityResolver;
    @Autowired private CompanionHub companion;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        RequestIdentity identity = identityResolver.resolve(authHeader, null);
        if (identity == null || identity.isGuest()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")   // nginx must pass each word on at once, not in batches
                .header("Cache-Control", "no-cache")
                .body(companion.subscribe(identity.uid()));
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 20_000)
    public void heartbeat() { companion.heartbeat(); }
}
