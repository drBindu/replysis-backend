package com.coopilotxai.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.coopilotxai.backend.service.ResumeTailorService;
import com.coopilotxai.backend.model.UserResume;
import com.coopilotxai.backend.repository.UserResumeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/resume")
/**
 * UPDATED CORS: 
 * Adding coopilotxai.com and using specific methods to ensure mobile browsers (Safari/Chrome) 
 * don't block the request.
 */
@CrossOrigin(
    origins = {
        "https://coopilotxai.com",
        "https://www.coopilotxai.com",
        "https://ai-powered-developer-assistance-platform.onrender.com",
        "http://localhost:3000"
    }, 
    allowedHeaders = "*", 
    methods = {RequestMethod.POST, RequestMethod.GET, RequestMethod.OPTIONS}
)
public class ResumeController {

    @Autowired
    private ResumeTailorService tailorService;

    @Autowired
    private UserResumeRepository resumeRepository;

    /**
     * 0. HEALTH CHECK
     * Open this on your phone to test connection: 
     * https://ai-powered-developer-assistance-platform-backend.onrender.com/api/v1/resume/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        return ResponseEntity.ok(Map.of("status", "Live", "database", "Connected"));
    }

    /**
     * 1. AI TAILORING ENDPOINT
     */
    @PostMapping("/tailor")
    public ResponseEntity<?> tailorResume(@RequestBody Map<String, Object> payload) {
        try {
            System.out.println("=== AI TAILORING REQUEST RECEIVED ===");
            
            String jd = (String) payload.get("jd");
            Map<String, Object> masterResume = (Map<String, Object>) payload.get("masterResume");

            if (jd == null || jd.isEmpty()) {
                return ResponseEntity.badRequest().body("Job description is required.");
            }

            // 1. Get the String from AI Service
            String tailoredJsonString = tailorService.generateTailoredMatter(masterResume, jd);

            // 2. IMPORTANT FIX: Convert String to a real JSON Object
            // This ensures the browser receives 'application/json' so r.json() works.
            ObjectMapper mapper = new ObjectMapper();
            Object jsonObject = mapper.readValue(tailoredJsonString, Object.class);

            System.out.println("AI Tailoring Successful!");
            return ResponseEntity.ok(jsonObject);

        } catch (Exception e) {
            System.err.println("AI ERROR: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("AI Error: " + e.getMessage());
        }
    }

    /**
     * 2. SAVE RESUME ENDPOINT
     */
    @PostMapping("/save")
    public ResponseEntity<?> saveResume(@RequestBody UserResume resume) {
        try {
            System.out.println("=== SAVING RESUME TO POSTGRESQL ===");
            UserResume savedResume = resumeRepository.save(resume);
            return ResponseEntity.ok(savedResume);
        } catch (Exception e) {
            System.err.println("SAVE ERROR: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Database Error: " + e.getMessage());
        }
    }

    /**
     * 3. LOAD RESUME ENDPOINT
     */
    @GetMapping("/load/{id}")
    public ResponseEntity<?> loadResume(@PathVariable Long id) {
        try {
            Optional<UserResume> resume = resumeRepository.findById(id);
            if (resume.isPresent()) {
                return ResponseEntity.ok(resume.get());
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Resume not found.");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Load Error: " + e.getMessage());
        }
    }
}