package com.shizuku.translate.repository;

import com.shizuku.translate.entity.PluginLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PluginLogRepository extends JpaRepository<PluginLog, Long> {

    Page<PluginLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<PluginLog> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);
}