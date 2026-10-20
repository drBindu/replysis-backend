package com.replysis.backend.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "user_resumes")
@Data // This automatically creates getters/setters
public class UserResume {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Firebase UID of the owner — set server-side on save, enforced on load
    private String userId;

    private String fullName;
    private String headline;
    private String email;
    private String phone;
    
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String skillCategories; // We'll store this as a string for now

    private String accentColor;
    private int fontSize;

    /**
     * The whole builder as JSON: every section, the template, fonts, colours, section order and custom sections.
     * The columns above only ever held a few fields, and experience, projects and education were never stored at all,
     * so nobody could get their resume back (2026-10-20). This is what the page loads.
     */
    @Column(columnDefinition = "TEXT")
    private String data;

    private java.time.Instant updatedAt;
}