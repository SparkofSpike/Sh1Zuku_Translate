package com.shizuku.translate.dto;

/**
 * A resolved glossary entry: a spelling in some source language paired with the corresponding
 * spelling in the current target language.
 */
public record GlossaryPair(String sourceTerm, String targetTerm) {
}
