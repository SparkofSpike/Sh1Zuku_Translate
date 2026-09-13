package com.shizuku.translate.config;

import com.shizuku.translate.entity.GlossaryConcept;
import com.shizuku.translate.entity.GlossaryTerm;
import com.shizuku.translate.repository.GlossaryConceptRepository;
import com.shizuku.translate.repository.GlossaryTermRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Seeds the glossary tables from {@code app.glossary} on startup.
 *
 * <p>Seeding is per-series and only when that series has no concepts yet, so edits made through
 * the (future) admin UI are never clobbered by a restart. The config file is the initial
 * population; the database is the runtime source of truth.
 */
@Component
@Order(20)
public class GlossarySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GlossarySeeder.class);

    private final AppConfig.AppProperties appProperties;
    private final GlossaryConceptRepository conceptRepository;
    private final GlossaryTermRepository termRepository;

    public GlossarySeeder(AppConfig.AppProperties appProperties,
                          GlossaryConceptRepository conceptRepository,
                          GlossaryTermRepository termRepository) {
        this.appProperties = appProperties;
        this.conceptRepository = conceptRepository;
        this.termRepository = termRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<AppConfig.AppProperties.GlossarySeriesItem> seriesList = appProperties.getGlossary();
        if (seriesList == null || seriesList.isEmpty()) {
            return;
        }
        for (AppConfig.AppProperties.GlossarySeriesItem series : seriesList) {
            if (series == null || series.getSeries() == null || series.getSeries().isBlank()) {
                continue;
            }
            if (conceptRepository.countBySeries(series.getSeries()) > 0) {
                log.debug("Glossary seed skipped for '{}': concepts already present", series.getSeries());
                continue;
            }
            int concepts = 0;
            int terms = 0;
            if (series.getConcepts() != null) {
                for (AppConfig.AppProperties.GlossaryConceptItem item : series.getConcepts()) {
                    if (item == null || item.getTerms() == null || item.getTerms().isEmpty()) {
                        continue;
                    }
                    GlossaryConcept concept = conceptRepository.save(GlossaryConcept.builder()
                            .series(series.getSeries())
                            .kind(item.getKind())
                            .note(item.getNote())
                            .build());
                    concepts++;
                    for (Map.Entry<String, String> entry : item.getTerms().entrySet()) {
                        String lang = entry.getKey();
                        String term = entry.getValue();
                        if (lang == null || lang.isBlank() || term == null || term.isBlank()) {
                            continue;
                        }
                        termRepository.save(GlossaryTerm.builder()
                                .conceptId(concept.getId())
                                .lang(lang.trim())
                                .term(term.trim())
                                .role("main")
                                .build());
                        terms++;
                    }
                }
            }
            log.info("Seeded glossary for '{}': {} concepts, {} terms", series.getSeries(), concepts, terms);
        }
    }
}
