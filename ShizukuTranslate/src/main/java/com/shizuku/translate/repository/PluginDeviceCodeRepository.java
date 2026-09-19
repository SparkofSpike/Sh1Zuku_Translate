package com.shizuku.translate.repository;

import com.shizuku.translate.entity.PluginDeviceCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PluginDeviceCodeRepository extends JpaRepository<PluginDeviceCode, Long> {

    Optional<PluginDeviceCode> findByCode(String code);

    boolean existsByCode(String code);
}
