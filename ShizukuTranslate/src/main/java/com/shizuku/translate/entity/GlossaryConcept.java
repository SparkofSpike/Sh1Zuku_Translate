package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A language-neutral translatable concept (character, place, or fixed term).
 *
 * <p>Per-language spellings live in {@link GlossaryTerm}. Anchoring on the concept instead of on a
 * language pair keeps growth linear: adding a language costs one row per concept, not one row per
 * (source, target) combination.
 */
@Entity
@Table(name = "glossary_concepts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlossaryConcept {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Work this concept belongs to; matches the preset name users pick in the UI. */
    @Column(nullable = false, length = 100)
    private String series;

    /** person | place | term | work */
    @Column(length = 20)
    private String kind;

    /**
     * Nicknames and short forms point at the concept they are derived from. Keeping them as
     * separate concepts (rather than aliases of one) is deliberate: "ヤチヨ" and "ヤチョ" are the
     * same character but must translate to different strings.
     */
    private Long relatedId;

    /** nickname_of | full_of | alias_of */
    @Column(length = 20)
    private String relationType;

    /** Human note; also flags machine-derived translations that still need review. */
    @Column(length = 500)
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
