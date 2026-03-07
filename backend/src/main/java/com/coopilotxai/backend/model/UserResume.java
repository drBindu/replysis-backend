package com.coopilotxai.backend.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "user_resumes")
@Data // This automatically creates getters/setters
public class UserResume {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
}