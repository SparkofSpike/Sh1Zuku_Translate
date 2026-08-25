package com.shizuku.translate.repository;

import com.shizuku.translate.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);

    /** Compatibility lookup for legacy rows only; new rows leave this column null after migration. */
    Optional<ApiKey> findByLegacyKeyValueAndActiveTrue(String legacyKeyValue);

    List<ApiKey> findByUserIdOrderByCreatedAtDesc(Long userId);
}
