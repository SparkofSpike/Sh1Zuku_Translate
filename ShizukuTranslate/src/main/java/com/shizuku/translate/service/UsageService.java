package com.shizuku.translate.service;

import com.shizuku.translate.dto.TokenUsage;
import com.shizuku.translate.entity.TokenUsageLog;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.integration.AiModelClient.AiModelConfig;
import com.shizuku.translate.repository.TokenUsageLogRepository;
import com.shizuku.translate.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UsageService {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TokenUsageLogRepository logRepository;
    private final UserRepository userRepository;

    public UsageService(TokenUsageLogRepository logRepository, UserRepository userRepository) {
        this.logRepository = logRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void record(User user, AiModelConfig config, TokenUsage usage) {
        if (usage == null) return;
        TokenUsageLog log = TokenUsageLog.builder()
                .user(user)
                .provider(config.getProvider())
                .model(config.getModel())
                .promptTokens(Math.max(0, usage.getPromptTokens()))
                .completionTokens(Math.max(0, usage.getCompletionTokens()))
                .totalTokens(Math.max(0, usage.getTotalTokens()))
                .createdAt(LocalDateTime.now())
                .build();
        logRepository.save(log);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUserUsage(String username) {
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return summaryResponse(logRepository.findByUserIdOrderByCreatedAtDesc(user.getId()), true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAdminUsage() {
        List<TokenUsageLog> logs = logRepository.findAll();
        Map<String, Object> response = summaryResponse(logs, true);
        Map<Long, User> users = new LinkedHashMap<>();
        userRepository.findAll().forEach(user -> users.put(user.getId(), user));

        Map<Long, List<TokenUsageLog>> byUser = new LinkedHashMap<>();
        logs.forEach(log -> byUser.computeIfAbsent(log.getUser().getId(), ignored -> new ArrayList<>()).add(log));
        List<Map<String, Object>> userRows = new ArrayList<>();
        users.values().stream()
                .sorted(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER))
                .forEach(user -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", user.getId());
                    row.put("username", user.getUsername());
                    row.put("email", user.getEmail());
                    row.putAll(summaryValues(byUser.getOrDefault(user.getId(), List.of())));
                    userRows.add(row);
                });
        response.put("users", userRows);
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAdminUserUsage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        List<TokenUsageLog> logs = logRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user", Map.of("id", user.getId(), "username", user.getUsername(), "email", user.getEmail()));
        response.put("summary", summaryValues(logs));
        response.put("logs", logs.stream().map(this::logResponse).toList());
        return response;
    }

    private Map<String, Object> summaryResponse(List<TokenUsageLog> logs, boolean includeCharts) {
        Map<String, Object> response = new LinkedHashMap<>(summaryValues(logs));
        if (includeCharts) {
            response.put("daily", dailyValues(logs));
            response.put("byModel", modelValues(logs));
        }
        return response;
    }

    private Map<String, Object> summaryValues(List<TokenUsageLog> logs) {
        long prompt = 0;
        long completion = 0;
        long total = 0;
        LocalDateTime latest = null;
        for (TokenUsageLog log : logs) {
            prompt += log.getPromptTokens();
            completion += log.getCompletionTokens();
            total += log.getTotalTokens();
            if (latest == null || log.getCreatedAt().isAfter(latest)) latest = log.getCreatedAt();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptTokens", prompt);
        result.put("completionTokens", completion);
        result.put("totalTokens", total);
        result.put("requestCount", logs.size());
        result.put("latestUsedAt", latest == null ? null : latest.toString());
        return result;
    }

    private List<Map<String, Object>> dailyValues(List<TokenUsageLog> logs) {
        Map<LocalDate, Long> totals = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int offset = 13; offset >= 0; offset--) totals.put(today.minusDays(offset), 0L);
        for (TokenUsageLog log : logs) {
            LocalDate date = log.getCreatedAt().toLocalDate();
            if (totals.containsKey(date)) totals.put(date, totals.get(date) + log.getTotalTokens());
        }
        return totals.entrySet().stream().map(entry -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("date", entry.getKey().format(DATE_FORMAT));
            value.put("totalTokens", entry.getValue());
            return value;
        }).toList();
    }

    private List<Map<String, Object>> modelValues(List<TokenUsageLog> logs) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (TokenUsageLog log : logs) {
            String key = log.getProvider() + ":" + log.getModel();
            totals.put(key, totals.getOrDefault(key, 0L) + log.getTotalTokens());
        }
        return totals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> {
                    String[] parts = entry.getKey().split(":", 2);
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("provider", parts[0]);
                    value.put("model", parts.length > 1 ? parts[1] : parts[0]);
                    value.put("totalTokens", entry.getValue());
                    return value;
                }).toList();
    }

    private Map<String, Object> logResponse(TokenUsageLog log) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", log.getId());
        result.put("provider", log.getProvider());
        result.put("model", log.getModel());
        result.put("promptTokens", log.getPromptTokens());
        result.put("completionTokens", log.getCompletionTokens());
        result.put("totalTokens", log.getTotalTokens());
        result.put("createdAt", log.getCreatedAt().toString());
        return result;
    }
}
