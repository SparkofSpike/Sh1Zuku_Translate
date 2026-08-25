package com.shizuku.translate.repository;

import com.shizuku.translate.entity.PersonalModelApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonalModelApiKeyRepository extends JpaRepository<PersonalModelApiKey, Long> {
    List<PersonalModelApiKey> findByUserIdOrderByCreatedAtAsc(Long userId);
    Optional<PersonalModelApiKey> findByIdAndUserId(Long id, Long userId);
}
