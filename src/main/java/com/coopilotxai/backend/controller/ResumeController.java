package com.coopilotxai.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.coopilotxai.backend.service.ResumeAnalysisService;
import com.coopilotxai.backend.service.ResumeTailorService;
import com.coopilotxai.backend.model.UserResume;
import com.coopilotxai.backend.repository.UserResumeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    @Autowired private ResumeTailorService   tailorService;
    @Autowired private ResumeAnalysisService analysisService;
    @Autowired private UserResumeRepository  resumeRepository;

    @Value("${pdf.service.url:http://localhost:3001}")
    private String pdfServiceUrl;

    // ─── 0. Health Check ───────────────────────────────────────────────────────
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        return ResponseEntity.ok(Map.of("status", "Live", "database", "Connected"));
    }

    // ─── 1. Missing Skills Analysis ────────────────────────────────────────────
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeResume(@RequestBody Map<String, Object> payload) {
        try {
            System.out.println("=== MISSING SKILLS ANALYSIS ===");
            String jd = (String) payload.get("jd");
            Map<String, Object> resume = (Map<String, Object>) payload.get("resume");

            if (jd == null || jd.trim().isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "Please enter a job description or keywords."));
            if (resume == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Resume data is missing."));

            Map<String, Object> result = analysisService.analyzeMissingSkills(resume, jd);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Analysis failed: " + e.getMessage()));
        }
    }

    // ─── 2. AI Tailor ──────────────────────────────────────────────────────────
    @PostMapping("/tailor")
    public ResponseEntity<?> tailorResume(@RequestBody Map<String, Object> payload) {
        try {
            System.out.println("=== AI TAILORING REQUEST ===");
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "AI Tailoring failed. Please try again in 30 seconds."));
        }
    }

    // ─── 3. Save Resume ────────────────────────────────────────────────────────
    @PostMapping("/save")
    public ResponseEntity<?> saveResume(@RequestBody UserResume resume) {
        try {
            System.out.println("=== SAVING RESUME ===");
            UserResume saved = resumeRepository.save(resume);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Database Error: " + e.getMessage()));
        }
    }

    // ─── 4. Load Resume ────────────────────────────────────────────────────────
    @GetMapping("/load/{id}")
    public ResponseEntity<?> loadResume(@PathVariable Long id) {
        try {
            Optional<UserResume> resume = resumeRepository.findById(id);
            if (resume.isPresent()) return ResponseEntity.ok(resume.get());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Resume not found."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Load Error: " + e.getMessage()));
        }
    }

    // ─── 5. Export PDF ─────────────────────────────────────────────────────────
    @PostMapping("/export-pdf")
    public ResponseEntity<?> exportPdf(@RequestBody Map<String, Object> payload) {
        try {
            System.out.println("=== EXPORT PDF ===");
            String html      = (String) payload.get("html");
            String paperSize = (String) payload.getOrDefault("paperSize", "a4");

            if (html == null || html.trim().isEmpty())
                return ResponseEntity.badRequest().body(Map.of("error", "HTML content is required."));

            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(Map.of("html", html, "paperSize", paperSize));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pdfServiceUrl + "/generate-pdf"))
                    .header("Content-Type", "application/json")
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
    public ResponseEntity<?> exportWord(@RequestBody Map<String, Object> payload) {
        try {
            System.out.println("=== EXPORT WORD ===");

            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(payload);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pdfServiceUrl + "/generate-word"))
                    .header("Content-Type", "application/json")
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
}