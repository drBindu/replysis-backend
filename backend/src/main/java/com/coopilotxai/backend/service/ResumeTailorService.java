package com.coopilotxai.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ResumeTailorService {

    @Value("${groq.api.key}")
    private String groqApiKey;

    private final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

    public String generateTailoredMatter(Map<String, Object> masterResume, String jd) {
        // Spoon-feed: We add a timeout so the app doesn't hang forever on Render
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000); // Wait up to 30 seconds for AI
        
        RestTemplate restTemplate = new RestTemplate(factory);
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            System.out.println("=== Starting AI Tailoring on Render ===");
            
            // Check if key is actually loaded
            if (groqApiKey == null || groqApiKey.isEmpty() || groqApiKey.contains("YOUR_ACTUAL_GROQ_KEY")) {
                System.err.println("CRITICAL ERROR: Groq API Key is MISSING in Render Environment!");
                return "{ \"error\": \"API Key configuration error on server.\" }";
            }

            // 1. Prepare Prompt
            String systemPrompt = "You are a Senior Technical Recruiter. Return ONLY valid JSON. " +
                                 "Include keys: personalInfo, summary, skillCategories, experience, projects. " +
                                 "Tailor content to this JD: " + jd;

            String userContent = "Current Resume JSON: " + objectMapper.writeValueAsString(masterResume);

            // 2. Build Request
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.3-70b-versatile");
            requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
            ));
            requestBody.put("response_format", Map.of("type", "json_object"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + groqApiKey.trim()); // trim to remove accidental spaces

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // 3. Call API
            ResponseEntity<Map> response = restTemplate.postForEntity(GROQ_API_URL, entity, Map.class);

            if (response.getBody() == null) {
                return "{ \"error\": \"AI returned an empty response.\" }";
            }

            // 4. Extract Content
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            // 5. Cleanup
            if (content.contains("```")) {
                content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            }

            System.out.println("=== AI SUCCESS ===");
            return content;

        } catch (Exception e) {
            System.err.println("AI TAILORING CRASHED: " + e.getMessage());
            e.printStackTrace();
            return "{ \"error\": \"Server error during tailoring: " + e.getMessage() + "\" }";
        }
    }
}