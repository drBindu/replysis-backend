package com.coopilotxai.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.coopilotxai.backend.security.FirebaseAuthService;
import com.coopilotxai.backend.security.AuthUser;
import com.coopilotxai.backend.service.ResumeAnalysisService;
import com.coopilotxai.backend.service.ResumeTailorService;
import com.coopilotxai.backend.model.UserResume;
import com.coopilotxai.backend.repository.UserResumeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseToken;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/resume")
public class ResumeController {

    @Autowired private FirebaseAuthService   firebaseAuthService;
    @Autowired private ResumeTailorService   tailorService;
    @Autowired private ResumeAnalysisService analysisService;
    @Autowired private UserResumeRepository  resumeRepository;

    @Value("${pdf.service.url:http://coopilotx-pdf-service:3001}")
    private String pdfServiceUrl;

    // Shared secret sent as X-Service-Token; the PDF service rejects requests
    // without it so it can never be driven by anything but this backend.
    @Value("${pdf.service.token:}")
    private String pdfServiceToken;

    // ─── 0. Health Check (public — no auth needed) ────────────────────────────
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        return ResponseEntity.ok(Map.of("status", "Live", "database", "Connected"));
    }

    // ─── 1. Missing Skills Analysis ────────────────────────────────────────────
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeResume(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> payload) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        try {
            System.out.println("=== MISSING SKILLS ANALYSIS uid=" + user.uid() + " ===");
            String jd = (String) payload.get("jd");
            Map<String, Object> resume = (Map<String, Object>) payload.get("resume");

            if (jd == null || jd.trim().isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "Please enter a job description or keywords."));
            if (resume == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Resume data is missing."));

            Map<String, Object> result = analysisService.analyzeMissingSkills(resume, jd);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Analysis failed: " + e.getMessage()));
        }
    }

    // ─── 2. AI Tailor ──────────────────────────────────────────────────────────
    @PostMapping("/tailor")
    public ResponseEntity<?> tailorResume(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> payload) {

        AuthUser user = verifyToken(authHeader);
        if (user == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));

        try {
            System.out.println("=== AI TAILORING REQUEST uid=" + user.uid() + " ===");
            String jd = (String) payload.get("jd");
            Map<String, Object> masterResume = (Map<String, Object>) payload.get("masterResume");
            List<String> selectedSkills = payload.containsKey("selectedSkills")
                    ? (List<String>) payload.get("selectedSkills") : List.of();
            List<String> sectionsToEnhance = payload.containsKey("sectionsToEnhance")
                    ? (List<String>) payload.get("sectionsToEnhance")
                    : List.of("summary", "skills", "experience", "projects");

            String provider = payload.containsKey("provider")
                    ? (String) payload.get("provider") : "groq";

            System.out.println("Provider selected: " + provider);

            if (jd == null || jd.trim().isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "Please enter a job description or keywords."));
            if (masterResume == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Resume data is missing."));

            String tailoredJsonString = tailorService.generateTailoredMatter(
                    masterResume, jd, selectedSkills, sectionsToEnhance, provider);

            ObjectMapper mapper = new ObjectMapper();
            Object jsonObject = mapper.readValue(tailoredJsonString, Object.class);
            System.out.println("AI Tailoring Successful!");
            return ResponseEntity.ok(jsonObject);

        } catch (RuntimeException e) {
            System.err.println("AI ERROR: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Database Error: " + e.getMessage()));
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Load Error: " + e.getMessage()));
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
                System.err.println("PDF service error: " + new String(response.body()));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "PDF service error"));
            }

            System.out.println("PDF generated successfully, size: " + response.body().length + " bytes");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=resume.pdf")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(response.body());

        } catch (Exception e) {
            System.err.println("PDF export error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PDF export failed: " + e.getMessage()));
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
                System.err.println("Word service error: " + new String(response.body()));
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
            System.err.println("Word export error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Word export failed: " + e.getMessage()));
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
            System.err.println("Token verification failed: " + e.getMessage());
            return null;
        }
    }

    // ── Helper: sanitize HTML to strip scripts/iframes/event-handlers ────────
    // Preserves all styling and layout elements — only removes dangerous content.
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
