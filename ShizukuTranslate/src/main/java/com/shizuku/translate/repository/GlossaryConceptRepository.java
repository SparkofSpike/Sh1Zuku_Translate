package com.shizuku.translate.repository;

import com.shizuku.translate.entity.GlossaryConcept;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GlossaryConceptRepository extends JpaRepository<GlossaryConcept, Long> {

    List<GlossaryConcept> findBySeries(String series);

    long countBySeries(String series);
}
