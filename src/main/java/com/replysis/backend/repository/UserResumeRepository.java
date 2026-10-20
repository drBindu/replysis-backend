package com.replysis.backend.repository;

import com.replysis.backend.model.UserResume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface UserResumeRepository extends JpaRepository<UserResume, Long> {
    // JpaRepository gives us save(), delete(), findById() automatically!

    // IDOR fix: only returns a resume if both id AND userId match (ownership check)
    Optional<UserResume> findByIdAndUserId(Long id, String userId);

    /** The person's resume: one row per person, the newest if older saves left several. */
    Optional<UserResume> findFirstByUserIdOrderByIdDesc(String userId);

    @Transactional
    long deleteAllByUserId(String userId);
}
