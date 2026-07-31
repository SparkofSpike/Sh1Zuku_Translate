package com.shizuku.translate.service;

import com.shizuku.translate.entity.ApiKey;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final UserService userService;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, UserService userService) {
        this.apiKeyRepository = apiKeyRepository;
        this.userService = userService;
    }

    @Transactional
    public ApiKey createApiKey(String username, String name) {
        User user = userService.findByUsername(username);

        ApiKey apiKey = ApiKey.builder()
                .keyValue("sk-" + UUID.randomUUID().toString().replace("-", ""))
                .name(name != null && !name.isBlank() ? name : "unnamed")
                .user(user)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusYears(1))
                .active(true)
                .build();

        return apiKeyRepository.save(apiKey);
    }

    public List<ApiKey> listApiKeys(String username) {
        User user = userService.findByUsername(username);
        return apiKeyRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public void deleteApiKey(String username, Long keyId) {
        User user = userService.findByUsername(username);
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("API key not found"));
        if (!apiKey.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("API key does not belong to user");
        }
        apiKeyRepository.delete(apiKey);
    }

    @Transactional
    public void deactivateApiKey(String username, Long keyId) {
        User user = userService.findByUsername(username);
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("API key not found"));
        if (!apiKey.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("API key does not belong to user");
        }
        apiKey.setActive(false);
        apiKeyRepository.save(apiKey);
    }
}
