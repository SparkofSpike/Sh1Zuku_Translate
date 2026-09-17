package com.shizuku.translate.repository;

import com.shizuku.translate.entity.Preset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PresetRepository extends JpaRepository<Preset, Long> {

    List<Preset> findAllByOrderByIdAsc();

    Optional<Preset> findByName(String name);

    boolean existsByName(String name);
}
