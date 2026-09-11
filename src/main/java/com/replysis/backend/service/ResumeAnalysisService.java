package com.replysis.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ResumeAnalysisService {

    private static final int MAX_RESUME_CHARS = 30_000;
    private static final int MAX_JOB_DESCRIPTION_CHARS = 6_000;

    // Was ${groq.api.key} with no default, so a missing Groq key stopped the
    // whole application booting. Groq is gone; this service ran entirely on it
    // with no fallback of any kind, so it was the last free-tier ceiling left
    // in a user-facing path.
    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    // Google's OpenAI-compatibility endpoint. Same request and response shape
    // the Groq call already used, so only the model, the key and two fields
    // below change.
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";

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
            if (resumeStr.length() > MAX_RESUME_CHARS || jd == null || jd.length() > MAX_JOB_DESCRIPTION_CHARS) {
                throw new IllegalArgumentException("Resume or job description exceeds the allowed size.");
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
            String content = callModel(sys, user);
            content = cleanJson(content);
            return om.readValue(content, Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) throw new RuntimeException("The analysis service is not available right now.");
            throw new RuntimeException("Analysis error: " + e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("Analysis failed: " + e.getMessage());
        }
    }

    private String callModel(String sys, String user) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        RestTemplate rt = new RestTemplate(factory);
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gemini-3.5-flash-lite");
        body.put("temperature", 0.1);
        body.put("max_tokens", 1_000);
        // The model reasons before it answers and bills that reasoning against
        // max_tokens, so on the default setting an analysis could spend most of
        // its thousand tokens thinking and return truncated JSON, which parses
        // as a failure rather than as a short answer.
        //
        // include_reasoning went with Groq. Gemini rejects it outright -
        // HTTP 400, "Unknown name \"include_reasoning\": Cannot find field" -
        // measured against the live endpoint, which is why it is deleted here
        // rather than left in place hoping it is ignored. reasoning_effort is
        // accepted and was verified in the same test.
        body.put("reasoning_effort", "low");
        body.put("messages", List.of(Map.of("role","system","content",sys), Map.of("role","user","content",user)));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + geminiApiKey);

        // Try with json_object, fallback without
        try {
            body.put("response_format", Map.of("type", "json_object"));
            ResponseEntity<Map> res = rt.postForEntity(GEMINI_URL, new HttpEntity<>(body, h), Map.class);
            List<Map<String,Object>> ch = (List<Map<String,Object>>) res.getBody().get("choices");
            return (String) ((Map<String,Object>) ch.get(0).get("message")).get("content");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getResponseBodyAsString().contains("response_format")) {
                body.remove("response_format");
                ResponseEntity<Map> res = rt.postForEntity(GEMINI_URL, new HttpEntity<>(body, h), Map.class);
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
