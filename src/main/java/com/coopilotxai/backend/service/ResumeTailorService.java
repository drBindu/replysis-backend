package com.coopilotxai.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ResumeTailorService {

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    private final String GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions";
    private final String GROQ_MODEL   = "llama-3.3-70b-versatile";
    private final String OPENAI_URL   = "https://api.openai.com/v1/chat/completions";
    private final String OPENAI_MODEL = "gpt-4o";
    private final String GEMINI_URL   = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    // ── Public entry points ──

    public String generateTailoredMatter(Map<String, Object> resume, String jd) {
        return generateTailoredMatter(resume, jd, List.of(),
                List.of("summary", "skills", "experience", "projects"), "groq");
    }

    public String generateTailoredMatter(Map<String, Object> resume, String jd,
                                          List<String> selectedSkills, List<String> sections) {
        return generateTailoredMatter(resume, jd, selectedSkills, sections, "groq");
    }

    public String generateTailoredMatter(Map<String, Object> resume, String jd,
                                          List<String> selectedSkills, List<String> sections,
                                          String provider) {
        ObjectMapper om = new ObjectMapper();
        try {
            String resolvedProvider = resolveProvider(provider);
            System.out.println("=== AI TAILORING REQUEST ===");
            System.out.println("Provider selected: " + resolvedProvider);
            System.out.println("Keys: Groq=" + hasKey(groqApiKey) + " OpenAI=" + hasKey(openAiApiKey) + " Gemini=" + hasKey(geminiApiKey));

            // Keep up to 4 bullets per item so AI has context but doesn't just copy 2
            Map<String, Object> slimResume = buildSlimResume(resume);
            String resumeJson = om.writeValueAsString(slimResume);

            String jdTrunc = (jd != null && jd.length() > 3000)
                    ? jd.substring(0, 3000) + "\n[JD truncated]"
                    : (jd != null ? jd : "");

            System.out.println("Slim resume : " + resumeJson.length() + " chars");
            System.out.println("JD (used)   : " + jdTrunc.length() + " chars");

            String skillsInstr = selectedSkills.isEmpty()
                ? "Extract EVERY skill, technology, tool, platform, and methodology from the JD."
                : "MUST include: [" + String.join(", ", selectedSkills) + "]. Also add ALL other JD skills.";

            // ── ENHANCED PROMPT — enforces rich, detailed output ──
            String sys = "You are a world-class resume tailoring expert. You produce DETAILED, RICH resumes.\n\n"
                + "RULES — follow EVERY rule precisely:\n\n"
                + "1. personalInfo: COPY EXACTLY. Never change name, email, phone, location, linkedin, github, portfolio, or customLinks.\n\n"
                + "2. headline: Rewrite to match JD role title + 4-6 JD technologies. Min 6 words.\n"
                + "   Example: \"Senior Java Developer | Spring Boot | AWS | Kubernetes | React | CI/CD\"\n\n"
                + "3. summary: " + (sections.contains("summary")
                    ? "Rewrite as 5-7 sentences, minimum 80 words. Must reference the JD role title and 6+ technologies from the JD. Include years of experience and 2-3 quantified achievements."
                    : "Keep exactly as-is.") + "\n\n"
                + "4. skillCategories: " + (sections.contains("skills")
                    ? "MUST produce AT LEAST 6 categories, each with 5-8 skills. Extract ALL technologies from the JD.\n"
                    + "   Required categories (adapt names to match JD):\n"
                    + "   - Languages (e.g., Java, Python, TypeScript, SQL, Go)\n"
                    + "   - Frameworks & Libraries (e.g., Spring Boot, React, Next.js, Express)\n"
                    + "   - Cloud & Infrastructure (e.g., AWS, Docker, Kubernetes, Terraform)\n"
                    + "   - Databases & Storage (e.g., PostgreSQL, MongoDB, Redis, DynamoDB)\n"
                    + "   - Testing & Quality (e.g., JUnit, Jest, Cypress, Selenium, SonarQube)\n"
                    + "   - Tools & Methodologies (e.g., Git, Jira, Agile, Scrum, CI/CD, Figma)\n"
                    + "   Add MORE categories if the JD mentions areas like ML/AI, Security, Mobile, etc."
                    : skillsInstr) + "\n\n"
                + "5. experience: " + (sections.contains("experience")
                    ? "Keep company, role, location, period EXACTLY as-is.\n"
                    + "   REWRITE bullets using STAR method. Each bullet MUST have a quantified metric.\n"
                    + "   *** MINIMUM 5 BULLETS PER JOB — ideally 6. Never fewer than 5. ***\n"
                    + "   Each bullet: 1-2 sentences, start with strong action verb, include % or numbers.\n"
                    + "   Examples of good bullets:\n"
                    + "   - \"Architected microservices handling 50K+ RPM using Spring Boot and Kubernetes, reducing latency by 40%.\"\n"
                    + "   - \"Led migration of legacy monolith to event-driven architecture, cutting deployment time from 2 hours to 15 minutes.\""
                    : "Keep exactly as-is.") + "\n\n"
                + "6. projects: " + (sections.contains("projects")
                    ? "Keep title, tech, period EXACTLY as-is.\n"
                    + "   *** MINIMUM 4 BULLETS PER PROJECT. ***\n"
                    + "   Update tech field to include relevant JD technologies.\n"
                    + "   Each bullet must include metrics (users, performance, time savings)."
                    : "Keep exactly as-is.") + "\n\n"
                + "7. education: COPY EXACTLY. Do not change.\n"
                + "8. certifications: COPY EXACTLY. Do not change.\n\n"
                + "BEFORE RESPONDING — verify these counts:\n"
                + "  ✓ Each experience entry has >= 5 bullets\n"
                + "  ✓ Each project entry has >= 4 bullets\n"
                + "  ✓ skillCategories has >= 6 categories\n"
                + "  ✓ Each skill category has >= 5 skills\n"
                + "  ✓ Summary is >= 80 words\n"
                + "  If ANY count is too low, ADD MORE before responding.\n\n"
                + "OUTPUT: Return ONLY a valid JSON object. No markdown. No backticks. No explanation.\n"
                + "Start with { and end with }.";

            String user = "Resume JSON:\n" + resumeJson + "\n\nJob Description:\n" + jdTrunc;

            String content = null;

            if ("openai".equals(resolvedProvider)) {
                System.out.println("Calling OpenAI...");
                content = callOpenAI(sys, user);
            } else if ("gemini".equals(resolvedProvider)) {
                System.out.println("Calling Gemini...");
                content = callGemini(sys, user);
            } else {
                System.out.println("Attempt 1: Groq JSON mode...");
                content = callGroq(sys, user, true);
                if (content == null || content.isBlank()) {
                    System.out.println("Attempt 2: Groq plain mode...");
                    content = callGroq(sys, user, false);
                }
            }

            if (content == null || content.isBlank()) {
                throw new RuntimeException("AI provider returned empty response. Try again in 30 seconds.");
            }

            content = cleanJson(content);
            System.out.println("Response length: " + content.length() + " chars");

            try { om.readTree(content); }
            catch (Exception parseEx) {
                System.err.println("JSON parse failed. Snippet: " + content.substring(0, Math.min(500, content.length())));
                throw new RuntimeException("AI returned malformed JSON. Please try again.");
            }

            System.out.println("=== TAILOR SUCCESS ===");
            return content;

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            System.err.println("HTTP ERROR: " + e.getStatusCode());
            System.err.println("BODY: " + e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED)
                throw new RuntimeException("API key is invalid or expired. Check your " + provider + " API key.");
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                throw new RuntimeException("Rate limited — wait 30s and try again, or switch AI provider.");
            throw new RuntimeException("AI API error (" + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            System.err.println("CONNECTION ERROR: " + e.getMessage());
            throw new RuntimeException("Cannot reach AI API. Check your internet connection.");
        } catch (RuntimeException e) {
            System.err.println("AI ERROR: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.err.println("UNKNOWN ERROR: " + e.getClass().getName() + ": " + e.getMessage());
            throw new RuntimeException("Tailoring failed: " + e.getMessage());
        }
    }

    // ── Provider resolver ──

    private boolean hasKey(String k) { return k != null && !k.isBlank(); }

    private String resolveProvider(String requested) {
        if (requested == null || requested.isBlank()) requested = "groq";
        String p = requested.toLowerCase().trim();

        switch (p) {
            case "openai":
                if (hasKey(openAiApiKey)) return "openai";
                System.out.println("'openai' key not configured, falling back silently...");
                if (hasKey(groqApiKey))   return "groq";
                if (hasKey(geminiApiKey)) return "gemini";
                break;
            case "gemini":
                if (hasKey(geminiApiKey)) return "gemini";
                System.out.println("'gemini' key not configured, falling back silently...");
                if (hasKey(groqApiKey))   return "groq";
                if (hasKey(openAiApiKey)) return "openai";
                break;
            case "groq":
            default:
                if (hasKey(groqApiKey))   return "groq";
                System.out.println("'groq' key not configured, falling back silently...");
                if (hasKey(openAiApiKey)) return "openai";
                if (hasKey(geminiApiKey)) return "gemini";
                break;
        }
        throw new RuntimeException("No AI API key configured. Add GROQ_API_KEY to your .env file and restart the backend.");
    }

    // ── Groq call ──

    private String callGroq(String sys, String user, boolean jsonMode) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", GROQ_MODEL);
            body.put("temperature", 0.3);
            body.put("max_tokens", 8000);
            body.put("messages", List.of(
                Map.of("role", "system", "content", sys),
                Map.of("role", "user",   "content", user)
            ));
            if (jsonMode) body.put("response_format", Map.of("type", "json_object"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + groqApiKey);

            RestTemplate rt = new RestTemplate();
            ResponseEntity<Map> res = rt.postForEntity(GROQ_URL, new HttpEntity<>(body, headers), Map.class);
            if (res.getBody() == null) return null;

            List<Map<String, Object>> choices = (List<Map<String, Object>>) res.getBody().get("choices");
            if (choices == null || choices.isEmpty()) return null;

            System.out.println("GROQ finish_reason: " + choices.get(0).get("finish_reason"));
            String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
            System.out.println("GROQ content length: " + (content != null ? content.length() : 0));
            return content;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            System.err.println("GROQ HTTP ERROR: " + e.getStatusCode());
            if (jsonMode && e.getResponseBodyAsString().contains("response_format")) return null;
            throw e;
        }
    }

    // ── OpenAI call ──

    private String callOpenAI(String sys, String user) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", OPENAI_MODEL);
        body.put("temperature", 0.3);
        body.put("max_tokens", 8000);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
            Map.of("role", "system", "content", sys),
            Map.of("role", "user",   "content", user)
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + openAiApiKey);

        RestTemplate rt = new RestTemplate();
        ResponseEntity<Map> res = rt.postForEntity(OPENAI_URL, new HttpEntity<>(body, headers), Map.class);
        if (res.getBody() == null) return null;

        List<Map<String, Object>> choices = (List<Map<String, Object>>) res.getBody().get("choices");
        if (choices == null || choices.isEmpty()) return null;

        System.out.println("OPENAI finish_reason: " + choices.get(0).get("finish_reason"));
        String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
        System.out.println("OPENAI content length: " + (content != null ? content.length() : 0));
        return content;
    }

    // ── Gemini call ──

    private String callGemini(String sys, String user) {
        try {
            String combined = sys + "\n\n" + user;
            Map<String, Object> part = Map.of("text", combined);
            Map<String, Object> contentObj = Map.of("parts", List.of(part));
            Map<String, Object> body = Map.of("contents", List.of(contentObj));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            RestTemplate rt = new RestTemplate();
            ResponseEntity<Map> res = rt.postForEntity(
                GEMINI_URL + "?key=" + geminiApiKey, new HttpEntity<>(body, headers), Map.class);
            if (res.getBody() == null) return null;

            List<Map<String, Object>> candidates = (List<Map<String, Object>>) res.getBody().get("candidates");
            if (candidates == null || candidates.isEmpty()) return null;

            Map<String, Object> contentMap = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) contentMap.get("parts");
            if (parts == null || parts.isEmpty()) return null;

            String content = (String) parts.get(0).get("text");
            System.out.println("GEMINI content length: " + (content != null ? content.length() : 0));
            return content;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            System.err.println("GEMINI HTTP ERROR: " + e.getStatusCode());
            throw e;
        }
    }

    // ── Build slim resume — keeps up to 4 bullets per item (was 2, now 4) ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSlimResume(Map<String, Object> resume) {
        try {
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(resume);
            Map<String, Object> slim = om.readValue(json, Map.class);

            List<Map<String, Object>> exp = (List<Map<String, Object>>) slim.get("experience");
            if (exp != null) {
                for (Map<String, Object> e : exp) {
                    List<String> bullets = (List<String>) e.get("bullets");
                    if (bullets != null && bullets.size() > 4)
                        e.put("bullets", bullets.subList(0, 4));
                }
            }

            List<Map<String, Object>> projects = (List<Map<String, Object>>) slim.get("projects");
            if (projects != null) {
                for (Map<String, Object> p : projects) {
                    List<String> bullets = (List<String>) p.get("bullets");
                    if (bullets != null && bullets.size() > 4)
                        p.put("bullets", bullets.subList(0, 4));
                }
            }

            return slim;
        } catch (Exception e) {
            System.err.println("buildSlimResume failed: " + e.getMessage());
            return resume;
        }
    }

    // ── Helpers ──

    private String cleanJson(String c) {
        if (c == null || c.isBlank()) return "{}";
        c = c.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
        int first = c.indexOf('{');
        int last  = c.lastIndexOf('}');
        if (first >= 0 && last > first) c = c.substring(first, last + 1);
        return c.trim();
    }

    private String buildSectionInstruction(List<String> s) {
        if (s.isEmpty()) return "Enhance all sections.";
        List<String> parts = new ArrayList<>();
        if (s.contains("summary"))    parts.add("rewrite Summary (5-7 sentences, 80+ words)");
        if (s.contains("skills"))     parts.add("enhance Skills (6+ categories, 5-8 skills each)");
        if (s.contains("experience")) parts.add("rewrite Experience (5-6 bullets per job with metrics)");
        if (s.contains("projects"))   parts.add("rewrite Projects (4+ bullets per project with metrics)");
        return String.join(", ", parts) + ".";
    }
}