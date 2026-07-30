package com.shizuku.translate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class AppConfig {

    @Bean
    @ConfigurationProperties(prefix = "app")
    public AppProperties appProperties() {
        return new AppProperties();
    }

    public static class AppProperties {
        private List<PresetItem> presets;
        private List<String> adminUsernames;

        public List<PresetItem> getPresets() { return presets; }
        public void setPresets(List<PresetItem> presets) { this.presets = presets; }
        public List<String> getAdminUsernames() { return adminUsernames; }
        public void setAdminUsernames(List<String> adminUsernames) { this.adminUsernames = adminUsernames; }

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

        public static class PresetItem {
            private String name;
            private String prompt;
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getPrompt() { return prompt; }
            public void setPrompt(String prompt) { this.prompt = prompt; }
        }
    }
}
