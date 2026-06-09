package com.coopilotxai.backend.repository;

import com.coopilotxai.backend.model.UserResume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserResumeRepository extends JpaRepository<UserResume, Long> {
    // JpaRepository gives us save(), delete(), findById() automatically!

    // IDOR fix: only returns a resume if both id AND userId match (ownership check)
    Optional<UserResume> findByIdAndUserId(Long id, String userId);
}