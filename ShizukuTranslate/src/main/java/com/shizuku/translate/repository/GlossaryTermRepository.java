package com.shizuku.translate.repository;

import com.shizuku.translate.dto.GlossaryPair;
import com.shizuku.translate.entity.GlossaryTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GlossaryTermRepository extends JpaRepository<GlossaryTerm, Long> {

    /**
     * Pairs every non-target spelling of a series' concepts with its target-language spelling.
     *
     * <p>Deliberately not filtered by source language: the caller does not know the source language
     * up front (it is auto-detected by the model), and every concept has only a handful of
     * spellings, so injecting all of them costs almost nothing while making the match independent
     * of which language the novel is written in. The right-hand side is always the target language,
     * so nothing from another target leaks into the prompt.
     */
    @Query("""
            SELECT new com.shizuku.translate.dto.GlossaryPair(src.term, dst.term)
            FROM GlossaryTerm src
            JOIN GlossaryConcept c ON src.conceptId = c.id
            JOIN GlossaryTerm dst ON dst.conceptId = c.id
            WHERE c.series = :series
              AND dst.lang = :targetLang
              AND src.lang <> :targetLang
            ORDER BY c.id, src.id
            """)
    List<GlossaryPair> findPairsForSeries(@Param("series") String series,
                                          @Param("targetLang") String targetLang);
}
