package com.replysis.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class ResumeTailorService {

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    // gpt-oss-120b on Cerebras: the paid model behind live answers, second choice for tailoring.
    @Value("${cerebras.api.key:}")
    private String cerebrasApiKey;

    // gemini-2.0-flash was RETIRED and answered 404 on every call - "This model
    // models/gemini-2.0-flash is no longer available". It was the fallback
    // behind Groq, so it had almost certainly never run: pulling the Groq key
    // would have failed every tailor request rather than degrading. Verified
    // against the live endpoint on 2026-09-11, both the old name failing and
    // this one answering.
    private final String GEMINI_BASE  = "https://generativelanguage.googleapis.com/v1beta/models/";
    // Tailoring rewrites a whole resume for a job and costs the user 4 answers, so it gets the stronger model
    // (owner, 2026-10-20: Lite only touched up wording, even for a very different role). Lite stays as the backup.
    private final String TAILOR_MODEL = "gemini-3.5-flash";
    private final String LITE_MODEL   = "gemini-3.5-flash-lite";

    // ── Public entry points ──

    public String generateTailoredMatter(Map<String, Object> resume, String jd) {
        return generateTailoredMatter(resume, jd, List.of(),
                List.of("summary", "skills", "experience", "projects"), "gemini");
    }

    public String generateTailoredMatter(Map<String, Object> resume, String jd,
                                          List<String> selectedSkills, List<String> sections) {
        return generateTailoredMatter(resume, jd, selectedSkills, sections, "gemini");
    }

    public String generateTailoredMatter(Map<String, Object> resume, String jd,
                                          List<String> selectedSkills, List<String> sections,
                                          String provider) {
        ObjectMapper om = new ObjectMapper();
        try {
            String resolvedProvider = resolveProvider(provider);
            System.out.println("=== AI TAILORING REQUEST ===");
            System.out.println("Provider selected: " + resolvedProvider);
            System.out.println("Keys: Gemini=" + hasKey(geminiApiKey));

            // Keep up to 4 bullets per item so AI has context but doesn't just copy 2
            Map<String, Object> slimResume = buildSlimResume(resume);
            String resumeJson = om.writeValueAsString(slimResume);

            String jdTrunc = (jd != null && jd.length() > 3500)
                    ? jd.substring(0, 3500) + "\n[JD truncated]"
                    : (jd != null ? jd : "");

            System.out.println("Slim resume : " + resumeJson.length() + " chars");
            System.out.println("JD (used)   : " + jdTrunc.length() + " chars");

            // Honest tailoring (owner, 2026-10-20): the old rules demanded 5+ bullets per job, a metric in every bullet
            // and every skill in the job post, so the model invented achievements and skills the person never had.
            // Recruiters check. It now rewords, reorders and emphasises what is really there, and invents nothing.
            String skillsInstr = selectedSkills.isEmpty()
                ? "Keep only skills the resume already shows. Put the ones the job post asks for first, using the job post's wording for the same thing."
                : "Keep only skills the resume already shows, plus these the person confirmed they have: [" + String.join(", ", selectedSkills) + "].";

            // ── ENHANCED PROMPT — enforces rich, detailed output ──
            String sys = "You are an expert resume editor. You tailor a real person's resume to a job post WITHOUT inventing anything.\n\n"
                + "THE ONE RULE ABOVE ALL: every fact must come from the resume. Never add an employer, title, date, number, metric, "
                + "percentage, achievement, tool or skill that is not in the resume. If a bullet has no number, do not add one.\n\n"
                + "RULES — follow EVERY rule precisely:\n\n"
                + "1. personalInfo: COPY EXACTLY, except headline (rule 2). Never change name, email, phone, location, linkedin, github, portfolio, or customLinks.\n\n"
                + "2. headline: ALWAYS rewrite it for this job. Start with the job's title as the post names it; if the post names no clear "
                + "title, name its focus in 2 to 5 words (for example \"AI Training and Enablement\"). Then 2 to 4 of the person's own "
                + "strengths or skills that match the job, most relevant first.\n"
                + "   Examples: \"Software Engineer, AI and ML | Python | AWS\", \"AI Training and Enablement | Mentoring | Full Stack Engineering\"\n\n"
                + "3. summary: " + (sections.contains("summary")
                    ? "Rewrite as 3-5 sentences for THIS role. The first sentence positions the person for the job using the job post's own words, "
                    + "built from the resume's most relevant real experience. If the job is not a pure engineering role (training, consulting, "
                    + "product, leadership), lead with the transferable parts the resume really shows, such as mentoring, knowledge sharing, "
                    + "working with product and business teams, and measurable results. Never claim experience the resume does not show."
                    : "Keep exactly as-is.") + "\n\n"
                + "4. skillCategories: " + (sections.contains("skills")
                    ? "Regroup the resume's own skills into clear categories (for example Languages, Frameworks, Cloud, Databases, Tools). "
                    + "Order each category so the skills the job post asks for come first. Add nothing the resume does not show."
                    : skillsInstr) + "\n\n"
                + "5. experience: " + (sections.contains("experience")
                    ? "Keep company, role, location, period EXACTLY as-is. Keep the same number of bullets.\n"
                    + "   Reword each bullet to start with a strong action verb and to bring forward what this job values most "
                    + "(for a training or client role: teaching, mentoring, communication, stakeholders, business outcomes). "
                    + "Keep every number exactly as written and add none. Order bullets so the most relevant come first."
                    : "Keep exactly as-is.") + "\n\n"
                + "6. projects: " + (sections.contains("projects")
                    ? "Keep title, tech, period EXACTLY as-is and the same number of bullets. Reword bullets for this job; add no numbers or technologies."
                    : "Keep exactly as-is.") + "\n\n"
                + "7. education: COPY EXACTLY. Do not change.\n"
                + "8. certifications: COPY EXACTLY. Do not change.\n\n"
                + "9. fit: add a top level field \"fit\" = {\"strengths\": [up to 4 short phrases: what in this resume matches the job], "
                + "\"gaps\": [up to 4 short phrases: what the job asks for that this resume does not show]}. Plain words, no dashes.\n\n"
                + "BEFORE RESPONDING, check: no number, skill, tool or achievement appears that was not in the resume.\n\n"
                + "OUTPUT: Return ONLY a valid JSON object. No markdown. No backticks. No explanation.\n"
                + "Start with { and end with }.";

            if (sections.contains("full_match")) {
                // Full match: the whole resume is rewritten toward the job. Verifiable facts (companies, dates,
                // numbers, schools, contact details) stay exactly as written; the safety net enforces that too.
                sys = "You are an expert resume writer. Rewrite this resume so it fully matches the job post. Every section must "
                    + "read as if written for this exact job, and every requirement and responsibility in the job post must appear "
                    + "somewhere in the resume.\n\n"
                    + "NEVER CHANGE: company names, employment dates, school names, degrees, certifications, contact details, and any "
                    + "number already in the resume (keep every number exactly). Never invent new numbers, percentages or metrics.\n\n"
                    + "RULES:\n"
                    + "1. personalInfo: copy exactly, except headline.\n"
                    + "2. headline: the job post's own job title, then 2 or 3 of its most important skills, separated by \" | \". At most 10 words. No dashes.\n"
                    + "3. summary: 3 to 5 sentences written for this job, using the job post's language and its key requirements, "
                    + "connected to what the person has done.\n"
                    + "4. skillCategories: every skill, tool and method the job post names, plus the resume's own relevant skills, "
                    + "grouped into clear categories, job post skills first.\n"
                    + "5. experience: keep company, location and period exactly. Rewrite each role title to the closest title for this "
                    + "job at the same level (for example Software Engineer becomes AI Solutions Engineer for an AI role). Rewrite "
                    + "EVERY bullet in the job post's language so it shows the responsibilities this job asks for, built on what the "
                    + "original bullet did. Keep at least as many bullets as before; add bullets where needed so each job "
                    + "responsibility appears at least once. Strong action verbs. No new numbers.\n"
                    + "6. projects: keep title and period; rewrite tech and bullets toward the job post's tools and goals.\n"
                    + "7. education and certifications: copy exactly.\n"
                    + "8. fit: add a top level field \"fit\" = {\"strengths\": [up to 4 short phrases: what the person really has that "
                    + "matches], \"gaps\": [up to 4 short phrases: what was reframed or added that the person must be ready to "
                    + "back up in an interview]}. Plain words, no dashes.\n\n"
                    + "OUTPUT: Return ONLY a valid JSON object with the same structure as the resume plus fit. No markdown.";
            }

            String user = "Resume JSON:\n" + resumeJson + "\n\nJob Description:\n" + jdTrunc;

            String content = null;

            System.out.println("Calling Gemini...");
            try {
                content = callGemini(sys, user, TAILOR_MODEL);
            } catch (RuntimeException strongFailed) {
                System.err.println("Tailor model failed (" + strongFailed.getClass().getSimpleName() + "), using the backup model");
                content = null;
            }
            if ((content == null || content.isBlank()) && cerebrasApiKey != null && !cerebrasApiKey.isBlank()) {
                try { content = callCerebras(sys, user); }
                catch (RuntimeException cerebrasFailed) {
                    System.err.println("Cerebras tailor failed (" + cerebrasFailed.getClass().getSimpleName() + "), using Lite");
                    content = null;
                }
            }
            if (content == null || content.isBlank()) content = callGemini(sys, user, LITE_MODEL);

            if (content == null || content.isBlank()) {
                throw new RuntimeException("AI provider returned empty response. Try again in 30 seconds.");
            }

            content = cleanJson(content);
            System.out.println("Response length: " + content.length() + " chars");

            try { content = keepWhatMustNotChange(om, resume, content); }
            catch (Exception parseEx) {
                // Log the shape of the failure, never the text: this content is the
                // candidate's tailored resume and must not land in server logs.
                System.err.println("JSON parse failed after cleanup (length=" + content.length() + ")");
                throw new RuntimeException("AI returned malformed JSON. Please try again.");
            }

            System.out.println("=== TAILOR SUCCESS ===");
            return content;

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // Status only. The response body can echo the prompt (and therefore the
            // resume) and is not safe to write to logs or to carry in the exception,
            // which the controller logs on the way out.
            System.err.println("Tailor provider rejected the request: HTTP " + e.getStatusCode());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED)
                throw new RuntimeException("The tailoring service is not available right now.");
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                throw new RuntimeException("The tailoring service is busy. Please try again in a moment.");
            throw new RuntimeException("The tailoring service returned an error (" + e.getStatusCode() + ").");
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // Deliberately not e.getMessage(): Spring puts the request URL in it,
            // and the Gemini call carries its API key as a query parameter, so the
            // message would write a live credential into the log.
            System.err.println("Tailor provider unreachable: " + e.getClass().getSimpleName());
            throw new RuntimeException("The tailoring service could not be reached. Please try again.");
        } catch (RuntimeException e) {
            System.err.println("Tailor failed: " + e.getClass().getSimpleName());
            throw e;
        } catch (Exception e) {
            System.err.println("Tailor failed unexpectedly: " + e.getClass().getSimpleName());
            throw new RuntimeException("Tailoring failed. Please try again.");
        }
    }

    /**
     * Turns the text of an uploaded resume (PDF, Word or plain text, read in the browser) into the builder's sections.
     * Copies, never writes: nothing is added, reworded or invented; anything missing is left empty.
     */
    public String importResume(String text) {
        String sys = "You convert the text of a resume into JSON for a resume builder. Copy the person's words exactly. "
            + "Do not reword, summarise, improve or invent anything. If something is missing, leave it empty.\n\n"
            + "Return ONLY this JSON object, no markdown:\n"
            + "{\"personalInfo\":{\"name\":\"\",\"headline\":\"\",\"email\":\"\",\"phone\":\"\",\"location\":\"\",\"linkedin\":\"\",\"github\":\"\",\"portfolio\":\"\",\"customLinks\":[]},"
            + "\"summary\":\"\",\"skillCategories\":[{\"name\":\"\",\"skills\":\"comma separated\"}],"
            + "\"experience\":[{\"company\":\"\",\"role\":\"\",\"location\":\"\",\"period\":\"\",\"bullets\":[\"\"]}],"
            + "\"projects\":[{\"title\":\"\",\"tech\":\"\",\"period\":\"\",\"bullets\":[\"\"]}],"
            + "\"education\":[{\"school\":\"\",\"degree\":\"\",\"period\":\"\",\"gpa\":\"\"}],"
            + "\"certifications\":[\"\"]}\n"
            + "headline is the person's title line under their name if there is one. Skills keep the resume's own grouping when it has one.";
        String content = callGemini(sys, "Resume text:\n" + text, LITE_MODEL);
        if (content == null || content.isBlank()) throw new RuntimeException("The import came back empty. Please try again.");
        content = cleanJson(content);
        try { new ObjectMapper().readTree(content); }
        catch (Exception e) { throw new RuntimeException("The import could not be read. Please try again."); }
        return content;
    }

    // ── Provider resolver ──

    private boolean hasKey(String k) { return k != null && !k.isBlank(); }

    private String resolveProvider(String requested) {
        // Every label resolves to Gemini. "openai" and "groq" are still accepted,
        // because callers send them, but neither provider is used. The resume
        // page defaulted to OpenAI, and this method sent that choice to an
        // account with no billing whenever a key was merely present, so every
        // tailor request from the default setting failed with a 429.
        if (hasKey(geminiApiKey)) return "gemini";
        throw new RuntimeException("No AI API key configured. Add GEMINI_API_KEY to your .env file and restart the backend.");
    }

    // ── Gemini call ──

    private String callGemini(String sys, String user, String model) {
        try {
            String combined = sys + "\n\n" + user;
            Map<String, Object> part = Map.of("text", combined);
            Map<String, Object> contentObj = Map.of("parts", List.of(part));
            // JSON mode: the answer is always a JSON object, so it cannot come back wrapped in prose or markdown.
            Map<String, Object> body = Map.of("contents", List.of(contentObj),
                "generationConfig", Map.of("responseMimeType", "application/json"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            RestTemplate rt = restTemplate();
            ResponseEntity<Map> res = rt.postForEntity(
                GEMINI_BASE + model + ":generateContent?key=" + geminiApiKey, new HttpEntity<>(body, headers), Map.class);
            if (res.getBody() == null) return null;

            List<Map<String, Object>> candidates = (List<Map<String, Object>>) res.getBody().get("candidates");
            if (candidates == null || candidates.isEmpty()) return null;

            Map<String, Object> contentMap = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) contentMap.get("parts");
            if (parts == null || parts.isEmpty()) return null;

            // Skip any thinking parts; join the answer parts.
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> p : parts) if (!Boolean.TRUE.equals(p.get("thought")) && p.get("text") != null) sb.append(p.get("text"));
            String content = sb.toString();
            System.out.println("GEMINI content length: " + (content != null ? content.length() : 0));
            return content;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            System.err.println("GEMINI HTTP ERROR: " + e.getStatusCode());
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private String callCerebras(String sys, String user) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", "gpt-oss-120b");
        body.put("messages", List.of(Map.of("role", "system", "content", sys), Map.of("role", "user", "content", user)));
        body.put("temperature", 0.3);
        body.put("max_tokens", 8000);
        body.put("reasoning_effort", "low");
        body.put("response_format", Map.of("type", "json_object"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cerebrasApiKey);
        ResponseEntity<Map> res = restTemplate().postForEntity("https://api.cerebras.ai/v1/chat/completions", new HttpEntity<>(body, headers), Map.class);
        if (res.getBody() == null) return null;
        List<Map<String, Object>> choices = (List<Map<String, Object>>) res.getBody().get("choices");
        if (choices == null || choices.isEmpty()) return null;
        Object text = ((Map<String, Object>) choices.get(0).get("message")).get("content");
        System.out.println("CEREBRAS content length: " + (text instanceof String t ? t.length() : 0));
        return text instanceof String t ? t : null;
    }

    // ── Build slim resume — keeps up to 4 bullets per item (was 2, now 4) ──

    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000);
        return new RestTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSlimResume(Map<String, Object> resume) {
        try {
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(resume);
            Map<String, Object> slim = om.readValue(json, Map.class);

            // Every bullet goes to the model. This used to keep only the first 4 per job, and the tailored resume
            // came back without the rest: a 5th bullet (often mentoring or leadership) was silently deleted.
            return slim;
        } catch (Exception e) {
            // This runs over the candidate's resume, so the exception message can
            // quote their data. Class name only.
            System.err.println("buildSlimResume failed: " + e.getClass().getSimpleName());
            return resume;
        }
    }

    // ── Safety net: whatever the model returns, contact details stay as written and no bullet is lost ──
    @SuppressWarnings("unchecked")
    private String keepWhatMustNotChange(ObjectMapper om, Map<String, Object> original, String content) throws Exception {
        Map<String, Object> out = om.readValue(content, Map.class);
        Map<String, Object> origInfo = (Map<String, Object>) original.get("personalInfo");
        if (origInfo != null) {
            Map<String, Object> info = new java.util.LinkedHashMap<>(origInfo);
            Object newInfo = out.get("personalInfo");
            if (newInfo instanceof Map && ((Map<String, Object>) newInfo).get("headline") instanceof String h && !h.isBlank())
                info.put("headline", h);
            out.put("personalInfo", info);
        }
        for (String key : List.of("experience", "projects")) {
            Object o = original.get(key), n = out.get(key);
            if (!(o instanceof List) || !(n instanceof List)) { if (o != null) out.put(key, o); continue; }
            List<Map<String, Object>> ol = (List<Map<String, Object>>) o, nl = (List<Map<String, Object>>) n;
            if (nl.size() < ol.size()) { out.put(key, ol); continue; }   // an entry went missing: keep the original section
            for (int i = 0; i < ol.size(); i++) {
                // Facts a background check verifies always come from the original.
                for (String f : List.of("company", "period", "location", "title")) {
                    if (key.equals("projects") && !f.equals("title") && !f.equals("period")) continue;
                    if (ol.get(i).containsKey(f)) nl.get(i).put(f, ol.get(i).get(f));
                }
                List<String> ob = (List<String>) ol.get(i).get("bullets"), nb = (List<String>) nl.get(i).get("bullets");
                if (ob != null && (nb == null || nb.size() < ob.size())) nl.get(i).put("bullets", ob);
            }
        }
        for (String key : List.of("education", "certifications"))
            if (original.containsKey(key)) out.put(key, original.get(key));
        for (Map.Entry<String, Object> e : original.entrySet()) out.putIfAbsent(e.getKey(), e.getValue());   // nothing the model skipped is lost
        return om.writeValueAsString(out);
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
