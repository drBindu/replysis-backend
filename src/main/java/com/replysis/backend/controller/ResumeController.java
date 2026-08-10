package com.replysis.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.replysis.backend.security.FirebaseAuthService;
import com.replysis.backend.security.AuthUser;
import com.replysis.backend.security.SimpleRateLimiter;
import com.replysis.backend.service.ResumeAnalysisService;
import com.replysis.backend.service.ResumeTailorService;
import com.replysis.backend.service.FirestoreCreditsService;
import com.replysis.backend.model.UserResume;
import com.replysis.backend.repository.UserResumeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseToken;

import jakarta.servlet.http.HttpServletRequest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/resume")
public class ResumeController {

    /**
     * Short reference shared between the log line and the response body, so a
     * support request can be traced to one failure without the response ever
     * carrying the exception behind it.
     */
    private static String correlationId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static final int ANALYSIS_CREDITS = 5;
    private static final int TAILOR_CREDITS = 20;
    private static final int MAX_JOB_DESCRIPTION_CHARS = 6_000;
    private static final int MAX_RESUME_JSON_CHARS = 30_000;

    @Autowired private FirebaseAuthService   firebaseAuthService;
    @Autowired private ResumeTailorService   tailorService;
    @Autowired private ResumeAnalysisService analysisService;
    @Autowired private UserResumeRepository  resumeRepository;
    @Autowired private FirestoreCreditsService creditsService;
    @Autowired private SimpleRateLimiter rateLimiter;

    @Value("${pdf.service.url:http://replysis-pdf-service:3001}")
    private String pdfServiceUrl;

    // Shared secret sent as X-Service-Token; the PDF service rejects requests
    // without it so it can never be driven by anything but this backend.
    @Value("${pdf.service.token:}")
    private String pdfServiceToken;

    @Value("${account.deletion.token:}")
    private String accountDeletionToken;

    // ─── 0. Health Check (public — no auth needed) ────────────────────────────
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        return ResponseEntity.ok(Map.of("status", "Live", "database", "Connected"));
    }

    // ─── 1. Missing Skills Analysis ────────────────────────────────────────────
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeResume(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request,
            @RequestBody Map<String, Object> payload) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        if (!allowAiRequest(user, request, "resume-analyze"))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many requests. Please try again shortly."));

        boolean charged = false;

        try {
            System.out.println("=== MISSING SKILLS ANALYSIS uid=" + user.uid() + " ===");
            String jd = boundedText(payload.get("jd"), MAX_JOB_DESCRIPTION_CHARS);
            Map<String, Object> resume = safeMap(payload.get("resume"), MAX_RESUME_JSON_CHARS);

            if (jd == null || jd.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "Please enter a job description or keywords."));
            if (resume == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Resume data is missing."));

            charged = creditsService.deductCredits(user.uid(), ANALYSIS_CREDITS);
            if (!charged)
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body(Map.of("error", "No credits remaining"));

            Map<String, Object> result = analysisService.analyzeMissingSkills(resume, jd);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            if (charged) creditsService.refundCredits(user.uid(), ANALYSIS_CREDITS);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Analysis failed. Please try again shortly."));
        }
    }

    // ─── 2. AI Tailor ──────────────────────────────────────────────────────────
    @PostMapping("/tailor")
    public ResponseEntity<?> tailorResume(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request,
            @RequestBody Map<String, Object> payload) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        if (!allowAiRequest(user, request, "resume-tailor"))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many requests. Please try again shortly."));

        boolean charged = false;

        try {
            System.out.println("=== AI TAILORING REQUEST uid=" + user.uid() + " ===");
            String jd = boundedText(payload.get("jd"), MAX_JOB_DESCRIPTION_CHARS);
            Map<String, Object> masterResume = safeMap(payload.get("masterResume"), MAX_RESUME_JSON_CHARS);
            List<String> selectedSkills = stringList(payload.get("selectedSkills"), 30, 80);
            List<String> sectionsToEnhance = stringList(payload.get("sectionsToEnhance"), 8, 32);
            if (selectedSkills == null || sectionsToEnhance == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid tailoring options."));
            if (sectionsToEnhance.isEmpty())
                sectionsToEnhance = List.of("summary", "skills", "experience", "projects");

            String provider = boundedText(payload.getOrDefault("provider", "groq"), 20);
            if (provider == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid provider."));
            provider = provider.toLowerCase();
            if (!List.of("groq", "openai", "gemini").contains(provider))
                return ResponseEntity.badRequest().body(Map.of("error", "Unsupported provider."));

            System.out.println("Provider selected: " + provider);

            if (jd == null || jd.isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "Please enter a job description or keywords."));
            if (masterResume == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Resume data is missing."));

            charged = creditsService.deductCredits(user.uid(), TAILOR_CREDITS);
            if (!charged)
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body(Map.of("error", "No credits remaining"));

            String tailoredJsonString = tailorService.generateTailoredMatter(
                    masterResume, jd, selectedSkills, sectionsToEnhance, provider);

            ObjectMapper mapper = new ObjectMapper();
            Object jsonObject = mapper.readValue(tailoredJsonString, Object.class);
            System.out.println("AI Tailoring Successful!");
            return ResponseEntity.ok(jsonObject);

        } catch (RuntimeException e) {
            if (charged) creditsService.refundCredits(user.uid(), TAILOR_CREDITS);
            System.err.println("Resume tailoring failed: " + e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "AI tailoring failed. Please try again shortly."));
        } catch (Exception e) {
            if (charged) creditsService.refundCredits(user.uid(), TAILOR_CREDITS);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "AI Tailoring failed. Please try again in 30 seconds."));
        }
    }

    // ─── 3. Save Resume ────────────────────────────────────────────────────────
    @PostMapping("/save")
    public ResponseEntity<?> saveResume(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody UserResume resume) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        try {
            System.out.println("=== SAVING RESUME uid=" + user.uid() + " ===");
            // Always stamp the authenticated user's UID — never trust client-supplied userId
            resume.setUserId(user.uid());
            UserResume saved = resumeRepository.save(resume);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            String ref = correlationId();
            System.err.println("Resume save failed ref=" + ref + " type=" + e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "We could not save your resume just now. Please try again.",
                                 "ref", ref));
        }
    }

    // ─── 4. Load Resume ────────────────────────────────────────────────────────
    @GetMapping("/load/{id}")
    public ResponseEntity<?> loadResume(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        try {
            // IDOR fix: findByIdAndUserId ensures users can only access their own resumes
            Optional<UserResume> resume = resumeRepository.findByIdAndUserId(id, user.uid());
            if (resume.isPresent()) return ResponseEntity.ok(resume.get());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Resume not found."));
        } catch (Exception e) {
            String ref = correlationId();
            System.err.println("Resume load failed ref=" + ref + " type=" + e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "We could not load your resume just now. Please try again.",
                                 "ref", ref));
        }
    }

    @DeleteMapping("/internal/users/{uid}")
    public ResponseEntity<?> deleteUserResumes(
            @RequestHeader(value = "X-Account-Deletion-Token", required = false) String token,
            @PathVariable String uid) {

        if (accountDeletionToken.isBlank() || token == null
                || !MessageDigest.isEqual(
                        accountDeletionToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (uid == null || !uid.matches("[^/\\u0000]{1,128}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid user"));
        }

        try {
            long deleted = resumeRepository.deleteAllByUserId(uid);
            return ResponseEntity.ok(Map.of("deleted", deleted));
        } catch (Exception e) {
            System.err.println("Account resume deletion failed: " + e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Resume deletion failed"));
        }
    }

    // ─── 5. Export PDF ─────────────────────────────────────────────────────────
    @PostMapping("/export-pdf")
    public ResponseEntity<?> exportPdf(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> payload) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        try {
            System.out.println("=== EXPORT PDF uid=" + user.uid() + " ===");
            String html      = (String) payload.get("html");
            String paperSize = (String) payload.getOrDefault("paperSize", "a4");

            if (html == null || html.trim().isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "HTML content is required."));

            // Sanitize HTML: strip scripts, iframes, objects, and event handlers
            // to prevent SSRF and XSS via the PDF renderer
            String cleanHtml = sanitizeHtml(html);

            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(Map.of("html", cleanHtml, "paperSize", paperSize));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pdfServiceUrl + "/generate-pdf"))
                    .header("Content-Type", "application/json")
                    .header("X-Service-Token", pdfServiceToken)
                    .timeout(java.time.Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                System.err.println("PDF service returned HTTP " + response.statusCode());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "PDF service error"));
            }

            System.out.println("PDF generated successfully, size: " + response.body().length + " bytes");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=resume.pdf")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(response.body());

        } catch (Exception e) {
            String ref = correlationId();
            System.err.println("PDF export failed ref=" + ref + " type=" + e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "We could not build your PDF just now. Please try again.",
                                 "ref", ref));
        }
    }

    // ─── 6. Export Word ────────────────────────────────────────────────────────
    @PostMapping("/export-word")
    public ResponseEntity<?> exportWord(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> payload) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        try {
            System.out.println("=== EXPORT WORD uid=" + user.uid() + " ===");

            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(payload);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pdfServiceUrl + "/generate-word"))
                    .header("Content-Type", "application/json")
                    .header("X-Service-Token", pdfServiceToken)
                    .timeout(java.time.Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                System.err.println("Word service returned HTTP " + response.statusCode());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Word service error"));
            }

            System.out.println("Word doc generated successfully, size: " + response.body().length + " bytes");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=resume.docx")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    .body(response.body());

        } catch (Exception e) {
            String ref = correlationId();
            System.err.println("Word export failed ref=" + ref + " type=" + e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "We could not build your Word document just now. Please try again.",
                                 "ref", ref));
        }
    }

    // ── Helper: verify Firebase Bearer token ────────────────────────────────
    private AuthUser verifyToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            String token = authHeader.substring("Bearer ".length()).trim();
            FirebaseToken decoded = firebaseAuthService.verify(token);
            return new AuthUser(decoded.getUid(), decoded.getEmail(), decoded.getName());
        } catch (Exception e) {
            System.err.println("Token verification failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    // ── Helper: sanitize HTML to strip scripts/iframes/event-handlers ────────
    // Preserves all styling and layout elements — only removes dangerous content.
    private boolean allowAiRequest(AuthUser user, HttpServletRequest request, String action) {
        return rateLimiter.tryAcquire(action + ":ip:" + rateLimiter.clientIp(request), 6, 60_000L)
                && rateLimiter.tryAcquire(action + ":user:" + user.uid(), 3, 60_000L);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value, int maximumJsonChars) {
        if (!(value instanceof Map<?, ?> map)) return null;
        try {
            String json = new ObjectMapper().writeValueAsString(map);
            if (json.length() > maximumJsonChars) return null;
            return (Map<String, Object>) map;
        } catch (Exception e) {
            return null;
        }
    }

    private String boundedText(Object value, int maximumLength) {
        if (!(value instanceof String text)) return null;
        String trimmed = text.trim();
        return trimmed.length() <= maximumLength ? trimmed : null;
    }

    private List<String> stringList(Object value, int maximumItems, int maximumItemLength) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> raw) || raw.size() > maximumItems) return null;
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String text)) return null;
            String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.length() > maximumItemLength) return null;
            values.add(trimmed);
        }
        return values;
    }

    private String sanitizeHtml(String html) {
        Document doc = Jsoup.parse(html);
        // Remove tags that can load external resources or execute code
        doc.select("script, iframe, object, embed, applet, base").remove();
        // Remove event handler attributes (onclick, onerror, onload, etc.)
        doc.getAllElements().forEach(el ->
            el.attributes().asList().stream()
                .filter(a -> a.getKey().toLowerCase().startsWith("on"))
                .map(org.jsoup.nodes.Attribute::getKey)
                .toList()
                .forEach(el::removeAttr)
        );
        // Remove javascript: and data: URIs from href/src/action attributes
        doc.select("[href],[src],[action]").forEach(el -> {
            for (String attr : List.of("href", "src", "action")) {
                String val = el.attr(attr).trim().toLowerCase();
                if (val.startsWith("javascript:") || val.startsWith("data:text/html")) {
                    el.removeAttr(attr);
                }
            }
        });
        return doc.outerHtml();
    }
}
