package com.coopilotxai.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ResumeAnalysisService {

    @Value("${groq.api.key}")
    private String groqApiKey;
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    /**
     * Analyze missing skills — works with BOTH JSON resume data AND raw extracted text.
     */
    public Map<String, Object> analyzeMissingSkills(Object resumeInput, String jd) {
        ObjectMapper om = new ObjectMapper();
        try {
            String resumeStr;
            if (resumeInput instanceof String) {
                resumeStr = (String) resumeInput; // raw text from docx/pdf
            } else {
                resumeStr = om.writeValueAsString(resumeInput); // JSON resume
            }

            String sys = "You are a resume analysis expert. Compare resume vs JD.\n\n"
                + "Extract EVERY skill, technology, tool, framework, language from JD.\n"
                + "Find which are missing in resume. Calculate match score 0-100.\n\n"
                + "Return ONLY this JSON (no markdown, no backticks):\n"
                + "{\"missingSkills\":[\"skill1\",\"skill2\"],\"presentSkills\":[\"skill1\"],\"matchScore\":72,\"jdTitle\":\"Job Title\"}\n\n"
                + "- missingSkills: ALL JD skills NOT in resume. Max 30. Most important first.\n"
                + "- presentSkills: JD skills in resume. Max 15.\n"
                + "- Be SPECIFIC: 'Kubernetes' not 'containers'.\n"
                + "- Return ONLY valid JSON.";

            String user = "Resume:\n" + resumeStr + "\n\nJob Description:\n" + jd;
            String content = callGroq(sys, user);
            content = cleanJson(content);
            return om.readValue(content, Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) throw new RuntimeException("Groq API key invalid.");
            throw new RuntimeException("Analysis error: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("Analysis failed: " + e.getMessage());
        }
    }

    private String callGroq(String sys, String user) {
        RestTemplate rt = new RestTemplate();
        Map<String, Object> body = new HashMap<>();
        body.put("model", "groq/compound-mini");
        body.put("temperature", 0.1);
        body.put("max_tokens", 2000);
        body.put("messages", List.of(Map.of("role","system","content",sys), Map.of("role","user","content",user)));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + groqApiKey);

        // Try with json_object, fallback without
        try {
            body.put("response_format", Map.of("type", "json_object"));
            ResponseEntity<Map> res = rt.postForEntity(GROQ_URL, new HttpEntity<>(body, h), Map.class);
            List<Map<String,Object>> ch = (List<Map<String,Object>>) res.getBody().get("choices");
            return (String) ((Map<String,Object>) ch.get(0).get("message")).get("content");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getResponseBodyAsString().contains("response_format")) {
                body.remove("response_format");
                ResponseEntity<Map> res = rt.postForEntity(GROQ_URL, new HttpEntity<>(body, h), Map.class);
                List<Map<String,Object>> ch = (List<Map<String,Object>>) res.getBody().get("choices");
                return (String) ((Map<String,Object>) ch.get(0).get("message")).get("content");
            }
            throw e;
        }
    }

    private String cleanJson(String c) {
        if (c == null) return "{}";
        c = c.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
        int f = c.indexOf('{'), l = c.lastIndexOf('}');
        if (f >= 0 && l > f) c = c.substring(f, l + 1);
        return c.trim();
    }
}