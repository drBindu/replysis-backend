package com.coopilotxai.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ResumeTailorService {

    @Value("${groq.api.key}")
    private String groqApiKey;

    private final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

    public String generateTailoredMatter(Map<String, Object> masterResume, String jd) {
        RestTemplate restTemplate = new RestTemplate();
        ObjectMapper objectMapper = new ObjectMapper();

        try {
            System.out.println("Starting AI Tailoring for JD length: " + jd.length());

            // 1. Prepare the Prompt for Groq
            // We tell the AI it MUST include the personalInfo so React doesn't crash on data.personalInfo.name
            String systemPrompt = "You are a Senior Technical Recruiter. " +
        "I will give you a Resume JSON and a Job Description. " +
        "TASK: Rewrite the following sections to match the JD exactly: " +
        "1. 'headline': Create a punchy, professional title that matches the Job Title in the JD. " +
        "2. 'summary': Tailor the professional summary to highlight matching experience. " +
        "3. 'skillCategories': Re-order and re-phrase the skills within each category to prioritize what the JD asks for. " +
        "4. 'bullets': Rewrite experience/project bullets using the STAR method focused on JD keywords. " +
        "CRITICAL: You MUST return the FULL JSON structure. Do not omit any sections. " +
        "IMPORTANT: Return ONLY the updated JSON. No backticks, no intro text.";

            String userContent = "Resume JSON: " + objectMapper.writeValueAsString(masterResume) + 
                                 "\n\nJob Description: " + jd;

            // 2. Build the Request Body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "llama-3.3-70b-versatile");
            
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userContent));
            
            requestBody.put("messages", messages);
            requestBody.put("response_format", Map.of("type", "json_object"));

            // 3. Set Headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + groqApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // 4. Call Groq API
            ResponseEntity<Map> response = restTemplate.postForEntity(GROQ_API_URL, entity, Map.class);

            // 5. Extract and CLEAN the content
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            // --- THE CLEANUP LOGIC (Prevents React Crashes) ---
            if (content.contains("```")) {
                content = content.replaceAll("(?s)```json\\s*", "")
                                 .replaceAll("(?s)```\\s*", "")
                                 .trim();
            }

            System.out.println("AI Response Cleaned Successfully.");
            return content;

        } catch (Exception e) {
            System.err.println("AI Tailoring Error: " + e.getMessage());
            return "{ \"error\": \"Failed to tailor resume: " + e.getMessage() + "\" }";
        }
    }
}