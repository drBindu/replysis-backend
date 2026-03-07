package com.coopilotxai.backend.repository;

import com.coopilotxai.backend.model.UserResume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserResumeRepository extends JpaRepository<UserResume, Long> {
    // JpaRepository gives us save(), delete(), findById() automatically!
}