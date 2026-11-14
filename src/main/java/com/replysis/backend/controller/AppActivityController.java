package com.replysis.backend.controller;

import com.replysis.backend.security.AppInfo;
import com.replysis.backend.security.IdentityResolverService;
import com.replysis.backend.security.RequestIdentity;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.AppDiaryService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The apps' activity diary: POST {"events":[{t, session_id, app, v, event, props}]}, at most 200 a request, answered 204.
 * The person is the one in the sign-in token; uid and app in the body are ignored. See AppDiaryService for what is kept.
 */
@RestController
@RequestMapping("/api/v1/activity")
public class AppActivityController {

    // A batch every 10 s is the design; this leaves room for a backlog sent after being offline, and no more.
    private static final int BATCHES_PER_MINUTE = 30;

    @Autowired private IdentityResolverService identityResolver;
    @Autowired private SimpleRateLimiter rateLimiter;
    @Autowired private AppDiaryService diary;

    @PostMapping
    public ResponseEntity<?> post(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        RequestIdentity identity = identityResolver.resolve(authHeader, null);
        if (identity == null || identity.uid() == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in first"));

        if (!rateLimiter.tryAcquire("activity-id:" + identity.uid(), BATCHES_PER_MINUTE, 60_000L))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").build();

        Object events = body == null ? null : body.get("events");
        if (!(events instanceof List<?> list))
            return ResponseEntity.badRequest().body(Map.of("error", "events must be a list"));
        if (list.size() > AppDiaryService.MAX_EVENTS)
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "at most 200 events a request"));

        diary.store(identity.uid(), AppInfo.parse(request.getHeader("X-App-Platform"), request.getHeader("X-App-Version")), list);
        return ResponseEntity.noContent().build();
    }
}
