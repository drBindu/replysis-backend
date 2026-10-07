package com.replysis.backend.controller;

import com.replysis.backend.service.ReadinessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Whether each part is awake, from the last keep-warm round. It makes no provider call of its own (the numbers are at most half a
 * minute old), so it is cheap enough to be polled by anyone, and it carries no keys and nothing about any customer.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @Autowired(required = false)
    private ReadinessService readiness;

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        if (readiness == null) {
            return ResponseEntity.ok(Map.of("systems", Map.of(), "note", "keep-warm is off here"));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(readiness.snapshot());
    }
}
