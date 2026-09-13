package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One language's spelling of a {@link GlossaryConcept}.
 */
@Entity
@Table(name = "glossary_terms",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_glossary_term_lang",
                columnNames = {"concept_id", "lang", "term"}),
        indexes = @Index(name = "idx_glossary_term_lookup", columnList = "lang, term"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlossaryTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "concept_id", nullable = false)
    private Long conceptId;

    /** BCP-47 short code: ja, zh-CN, ko, vi. */
    @Column(nullable = false, length = 10)
    private String lang;

    /** Spelling used in text of this language. */
    @Column(nullable = false, length = 200)
    private String term;

    /** main | full | short | latin */
    @Column(length = 20)
    private String role;
}
