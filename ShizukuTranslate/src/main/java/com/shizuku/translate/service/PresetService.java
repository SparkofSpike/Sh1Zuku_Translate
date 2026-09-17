package com.shizuku.translate.service;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.entity.Preset;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.exception.ResourceNotFoundException;
import com.shizuku.translate.repository.GlossaryConceptRepository;
import com.shizuku.translate.repository.PresetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runtime source of truth for translation presets.
 *
 * <p>{@code app.presets} in the configuration file only seeds the table on
 * first startup (per name, only while that name is absent), the same pattern
 * the glossary seeder uses: config file = initial population, database =
 * runtime truth. Admin CRUD edits therefore survive a restart.</p>
 */
@Service
@Order(30)
public class PresetService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PresetService.class);

    private final PresetRepository presetRepository;
    private final GlossaryConceptRepository glossaryConceptRepository;
    private final AppConfig.AppProperties appProperties;

    public PresetService(PresetRepository presetRepository,
                         GlossaryConceptRepository glossaryConceptRepository,
                         AppConfig.AppProperties appProperties) {
        this.presetRepository = presetRepository;
        this.glossaryConceptRepository = glossaryConceptRepository;
        this.appProperties = appProperties;
    }

    /** Preset names offered to clients, in stable insertion order. */
    @Transactional(readOnly = true)
    public List<String> listNames() {
        return presetRepository.findAllByOrderByIdAsc().stream()
                .map(Preset::getName)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Preset> listAll() {
        return presetRepository.findAllByOrderByIdAsc();
    }

    @Transactional
    public Preset create(String name, String prompt) {
        String trimmedName = requireName(name);
        String trimmedPrompt = requirePrompt(prompt);
        if (presetRepository.existsByName(trimmedName)) {
            throw new BusinessException("同名预设已存在：" + trimmedName);
        }
        Preset saved = presetRepository.save(Preset.builder()
                .name(trimmedName)
                .prompt(trimmedPrompt)
                .build());
        log.info("Preset created: {}", saved.getName());
        return saved;
    }

    @Transactional
    public Preset update(Long id, String name, String prompt) {
        Preset preset = presetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Preset not found"));
        String trimmedName = requireName(name);
        String trimmedPrompt = requirePrompt(prompt);
        presetRepository.findByName(trimmedName)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException("同名预设已存在：" + trimmedName);
                });
        preset.setName(trimmedName);
        preset.setPrompt(trimmedPrompt);
        Preset saved = presetRepository.save(preset);
        log.info("Preset updated: {}", saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Preset preset = presetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Preset not found"));
        // A preset name doubles as the glossary series key; warn instead of
        // blocking so deleting a preset never bricks the admin workflow.
        if (glossaryConceptRepository.countBySeries(preset.getName()) > 0) {
            log.warn("Deleting preset '{}' which is also a glossary series; its terminology stays in place", preset.getName());
        }
        presetRepository.delete(preset);
        log.info("Preset deleted: {}", preset.getName());
    }

    /**
     * Seed the table from {@code app.presets} on first startup. Seeding is
     * per name and only while that name is absent, so admin edits made later
     * are never clobbered by a restart.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<AppConfig.AppProperties.PresetItem> seeds = appProperties.getPresets();
        if (seeds == null || seeds.isEmpty()) {
            return;
        }
        int created = 0;
        for (AppConfig.AppProperties.PresetItem item : seeds) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (presetRepository.existsByName(item.getName().trim())) {
                continue;
            }
            if (item.getPrompt() == null || item.getPrompt().isBlank()) {
                continue;
            }
            presetRepository.save(Preset.builder()
                    .name(item.getName().trim())
                    .prompt(item.getPrompt())
                    .build());
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} presets from configuration", created);
        }
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isBlank()) {
            throw new BusinessException("预设名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100) {
            throw new BusinessException("预设名称不能超过100个字符");
        }
        return trimmed;
    }

    private static String requirePrompt(String prompt) {
        if (prompt == null || prompt.trim().isBlank()) {
            throw new BusinessException("预设内容不能为空");
        }
        return prompt.trim();
    }
}
