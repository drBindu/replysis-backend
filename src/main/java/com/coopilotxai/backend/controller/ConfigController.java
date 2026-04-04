package com.coopilotxai.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@CrossOrigin
public class ConfigController {

    @Value("${groq.api.key:}")
    private String groqKey;

    @Value("${gemini.api.key:}")
    private String geminiKey;

    @Value("${openai.api.key:}")
    private String openaiKey;

    @Value("${speechmatics.api.key:}")
    private String speechmaticsKey;

    @GetMapping("/keys")
    public Map<String, String> getKeys() {
        Map<String, String> keys = new HashMap<>();
        if (!groqKey.isBlank()) keys.put("groqKey", groqKey);
        if (!geminiKey.isBlank()) keys.put("geminiKey", geminiKey);
        if (!openaiKey.isBlank()) keys.put("openaiKey", openaiKey);
        if (!speechmaticsKey.isBlank()) keys.put("speechmaticsKey", speechmaticsKey);
        return keys;
    }
}