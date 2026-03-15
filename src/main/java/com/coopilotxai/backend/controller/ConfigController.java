package com.coopilotxai.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Value("${gemini.api.key}")
    private String geminiKey;

    @Value("${speechmatics.api.key}")
    private String speechmaticsKey;

    @GetMapping("/keys")
    public Map<String, String> getKeys() {
        Map<String, String> keys = new HashMap<>();
        // These match the names we will use in C#
        keys.put("geminiKey", geminiKey);
        keys.put("speechmaticsKey", speechmaticsKey);
        return keys;
    }
}