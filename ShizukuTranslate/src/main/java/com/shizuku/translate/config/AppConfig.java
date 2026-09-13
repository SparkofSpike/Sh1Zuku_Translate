package com.shizuku.translate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class AppConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConfigurationProperties(prefix = "app")
    public AppProperties appProperties() {
        return new AppProperties();
    }

    public static class AppProperties {
        private List<PresetItem> presets;
        private List<String> adminUsernames;
        private TranslationProperties translation;
        private List<GlossarySeriesItem> glossary;

        public List<PresetItem> getPresets() { return presets; }
        public void setPresets(List<PresetItem> presets) { this.presets = presets; }
        public List<String> getAdminUsernames() { return adminUsernames; }
        public void setAdminUsernames(List<String> adminUsernames) { this.adminUsernames = adminUsernames; }
        public TranslationProperties getTranslation() { return translation; }
        public void setTranslation(TranslationProperties translation) { this.translation = translation; }
        public List<GlossarySeriesItem> getGlossary() { return glossary; }
        public void setGlossary(List<GlossarySeriesItem> glossary) { this.glossary = glossary; }

        public Map<String, String> getPresetMap() {
            if (presets == null) return Map.of();
            return presets.stream()
                    .collect(Collectors.toMap(PresetItem::getName, PresetItem::getPrompt));
        }

        public List<String> getPresetNames() {
            if (presets == null) return List.of();
            return presets.stream().map(PresetItem::getName).collect(Collectors.toList());
        }

        public boolean isAdmin(String username) {
            return adminUsernames != null && adminUsernames.contains(username);
        }

        /** Target languages offered to clients; the first entry is not implicitly the default. */
        public List<LanguageItem> getTargetLanguages() {
            if (translation == null || translation.getTargetLanguages() == null) return List.of();
            return translation.getTargetLanguages();
        }

        public String getDefaultTargetLanguage() {
            if (translation != null && translation.getDefaultTargetLanguage() != null
                    && !translation.getDefaultTargetLanguage().isBlank()) {
                return translation.getDefaultTargetLanguage();
            }
            return "zh-CN";
        }

        /**
         * Resolves a client-supplied language tag to a configured one, falling back to the default
         * so an unknown or absent value keeps the historical behaviour (translate into Chinese)
         * instead of failing the request.
         */
        public String resolveTargetLanguage(String requested) {
            if (requested == null || requested.isBlank()) return getDefaultTargetLanguage();
            String wanted = requested.trim();
            for (LanguageItem item : getTargetLanguages()) {
                if (item.getCode() != null && item.getCode().equalsIgnoreCase(wanted)) {
                    return item.getCode();
                }
            }
            return getDefaultTargetLanguage();
        }

        /** Human-readable language name used inside prompts, e.g. "简体中文" or "Tiếng Việt". */
        public String targetLanguageLabel(String code) {
            for (LanguageItem item : getTargetLanguages()) {
                if (item.getCode() != null && item.getCode().equalsIgnoreCase(code)) {
                    return (item.getLabel() != null && !item.getLabel().isBlank()) ? item.getLabel() : code;
                }
            }
            return code;
        }

        public static class PresetItem {
            private String name;
            private String prompt;
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getPrompt() { return prompt; }
            public void setPrompt(String prompt) { this.prompt = prompt; }
        }

        public static class LanguageItem {
            private String code;
            private String label;
            public String getCode() { return code; }
            public void setCode(String code) { this.code = code; }
            public String getLabel() { return label; }
            public void setLabel(String label) { this.label = label; }
        }

        public static class TranslationProperties {
            private String defaultTargetLanguage;
            private List<LanguageItem> targetLanguages;
            public String getDefaultTargetLanguage() { return defaultTargetLanguage; }
            public void setDefaultTargetLanguage(String defaultTargetLanguage) { this.defaultTargetLanguage = defaultTargetLanguage; }
            public List<LanguageItem> getTargetLanguages() { return targetLanguages; }
            public void setTargetLanguages(List<LanguageItem> targetLanguages) { this.targetLanguages = targetLanguages; }
        }

        /** Seed data for the glossary tables; {@code series} matches a preset name. */
        public static class GlossarySeriesItem {
            private String series;
            private List<GlossaryConceptItem> concepts;
            public String getSeries() { return series; }
            public void setSeries(String series) { this.series = series; }
            public List<GlossaryConceptItem> getConcepts() { return concepts; }
            public void setConcepts(List<GlossaryConceptItem> concepts) { this.concepts = concepts; }
        }

        public static class GlossaryConceptItem {
            private String kind;
            private String note;
            /** Language code to spelling, e.g. {ja: ヤチヨ, zh-CN: 八千代, vi: Yachiyo}. */
            private Map<String, String> terms;
            public String getKind() { return kind; }
            public void setKind(String kind) { this.kind = kind; }
            public String getNote() { return note; }
            public void setNote(String note) { this.note = note; }
            public Map<String, String> getTerms() { return terms; }
            public void setTerms(Map<String, String> terms) { this.terms = terms; }
        }
    }
}
