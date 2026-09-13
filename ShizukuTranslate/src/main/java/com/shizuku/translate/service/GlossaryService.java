package com.shizuku.translate.service;

import com.shizuku.translate.dto.GlossaryPair;
import com.shizuku.translate.repository.GlossaryTermRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders a series' glossary into the fragment appended to a translation system prompt.
 *
 * <p>Rendering is intentionally one-directional: the right-hand side of every pair is the current
 * target language. Because the source language is auto-detected rather than declared, all known
 * source spellings of a concept are injected — at the current scale that is a handful of extra
 * tokens, and it makes the match work regardless of whether the novel is Japanese, Korean, or
 * Chinese.
 */
@Service
public class GlossaryService {

    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    /**
     * Guard against a runaway glossary silently inflating every request's prompt. At the current
     * scale (tens of entries per series) this never triggers.
     */
    private static final int MAX_ENTRIES = 500;

    private final GlossaryTermRepository termRepository;

    public GlossaryService(GlossaryTermRepository termRepository) {
        this.termRepository = termRepository;
    }

    /**
     * @return the glossary fragment for {@code series} rendered into {@code targetLang}, or
     *         {@code null} when that series has no glossary entry for the target language.
     */
    public String buildSeriesInjection(String series, String targetLang) {
        if (series == null || series.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        List<GlossaryPair> pairs;
        try {
            pairs = termRepository.findPairsForSeries(series, targetLang);
        } catch (Exception e) {
            // A broken or empty glossary must never break translation.
            log.warn("Glossary lookup failed for series '{}' target '{}': {}", series, targetLang, e.toString());
            return null;
        }
        if (pairs == null || pairs.isEmpty()) {
            return null;
        }

        Set<String> rendered = new LinkedHashSet<>();
        for (GlossaryPair pair : pairs) {
            if (pair == null || pair.sourceTerm() == null || pair.targetTerm() == null) continue;
            if (pair.sourceTerm().isBlank() || pair.targetTerm().isBlank()) continue;
            rendered.add(pair.sourceTerm() + " -> " + pair.targetTerm());
            if (rendered.size() >= MAX_ENTRIES) {
                log.warn("Glossary for series '{}' exceeds {} entries; truncating", series, MAX_ENTRIES);
                break;
            }
        }
        if (rendered.isEmpty()) {
            return null;
        }
        return "【【" + series + " 译名参考】】：" + String.join("、", rendered) + "。";
    }
}
