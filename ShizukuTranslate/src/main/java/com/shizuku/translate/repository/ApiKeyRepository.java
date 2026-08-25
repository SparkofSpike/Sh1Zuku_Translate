package com.shizuku.translate.repository;

import com.shizuku.translate.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);

    Optional<ApiKey> findByKeyValueAndActiveTrue(String keyValue);

    List<ApiKey> findByUserIdOrderByCreatedAtDesc(Long userId);
}
