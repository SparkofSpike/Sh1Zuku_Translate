package com.shizuku.translate.service;

import com.shizuku.translate.config.DeepSeekConfig;
import com.shizuku.translate.dto.LoginRequest;
import com.shizuku.translate.dto.RegisterRequest;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.integration.AiModelClient.AiModelConfig;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.UserRepository;
import com.shizuku.translate.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final DeepSeekConfig.DeepSeekProperties deepSeekProperties;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       DeepSeekConfig.DeepSeekProperties deepSeekProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.deepSeekProperties = deepSeekProperties;
    }

    public void register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim();
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new RuntimeException("Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
    }

    public String login(LoginRequest request) {
        String username = request.getUsername().trim();
        User user;

        if (username.contains("@")) {
            user = userRepository.findByEmailIgnoreCase(username)
                    .orElseThrow(() -> new RuntimeException("Invalid username or password"));
        } else {
            user = userRepository.findByUsernameIgnoreCase(username)
                    .orElseThrow(() -> new RuntimeException("Invalid username or password"));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Invalid username or password");
        }
        return tokenProvider.generateToken(user.getUsername());
    }

    public User findByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public long getUserCount() {
        return userRepository.count();
    }

    public String getAiApiKey(String username) {
        return findByUsername(username).getAiApiKey();
    }

    public String getAiApiKeyPreview(String username) {
        String key = findByUsername(username).getAiApiKey();
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 4) return "••••";
        return key.substring(0, 2) + "..." + key.substring(key.length() - 2);
    }

    public void updateAiApiKey(String username, String aiApiKey) {
        User user = findByUsername(username);
        user.setAiApiKey(aiApiKey == null || aiApiKey.isBlank() ? null : aiApiKey.trim());
        if (user.getAiApiKey() == null) {
            user.setAiProvider(null);
            user.setAiBaseUrl(null);
            user.setAiModel(null);
        }
        userRepository.save(user);
    }

    public void updateAiModelConfig(String username, String provider, String baseUrl, String model, String apiKey) {
        User user = findByUsername(username);
        String normalizedProvider = provider == null || provider.isBlank()
                ? "deepseek" : provider.trim().toLowerCase();
        if (!normalizedProvider.equals("deepseek")
                && !normalizedProvider.equals("openai")
                && !normalizedProvider.equals("anthropic")) {
            throw new BusinessException("不支持的模型协议");
        }
        if (apiKey != null && !apiKey.isBlank()) {
            user.setAiApiKey(apiKey.trim());
        }
        String normalizedBaseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim();
        if (normalizedBaseUrl == null && normalizedProvider.equals("openai")) {
            normalizedBaseUrl = "https://api.openai.com/v1";
        } else if (normalizedBaseUrl == null && normalizedProvider.equals("anthropic")) {
            normalizedBaseUrl = "https://api.anthropic.com/v1";
        }
        String normalizedModel = model == null || model.isBlank() ? null : model.trim();
        if (!normalizedProvider.equals("deepseek") && (user.getAiApiKey() == null || user.getAiApiKey().isBlank())) {
            throw new BusinessException("OpenAI 兼容和 Anthropic 兼容模型必须配置 API Key");
        }
        if (!normalizedProvider.equals("deepseek") && (normalizedBaseUrl == null || normalizedBaseUrl.isBlank())) {
            throw new BusinessException("兼容协议必须配置 Base URL");
        }
        if (!normalizedProvider.equals("deepseek") && (normalizedModel == null || normalizedModel.isBlank())) {
            throw new BusinessException("兼容协议必须配置模型名称");
        }
        user.setAiProvider(normalizedProvider);
        user.setAiBaseUrl(normalizedBaseUrl);
        user.setAiModel(normalizedModel);
        userRepository.save(user);
    }

    private String defaultBaseUrl(String provider) {
        if ("openai".equals(provider)) return "https://api.openai.com/v1";
        if ("anthropic".equals(provider)) return "https://api.anthropic.com/v1";
        return deepSeekProperties.getBaseUrl();
    }

    public AiModelConfig resolveAiModelConfig(User user, String requestedModel, String thinkingType) {
        boolean personalKey = user.getAiApiKey() != null && !user.getAiApiKey().isBlank();
        String provider = personalKey && user.getAiProvider() != null && !user.getAiProvider().isBlank()
                ? user.getAiProvider().toLowerCase() : "deepseek";
        String apiKey = personalKey ? user.getAiApiKey().trim() : deepSeekProperties.getKey();
        String baseUrl = personalKey && user.getAiBaseUrl() != null && !user.getAiBaseUrl().isBlank()
                ? user.getAiBaseUrl().trim() : defaultBaseUrl(provider);
        String model = requestedModel != null && !requestedModel.isBlank()
                ? requestedModel.trim()
                : (user.getAiModel() != null && !user.getAiModel().isBlank()
                    ? user.getAiModel().trim() : deepSeekProperties.getDefaultModel());

        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("请先在个人页面配置模型 API Key");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException("请先在个人页面配置模型 Base URL");
        }
        if (model == null || model.isBlank()) {
            throw new BusinessException("请先在个人页面配置模型名称");
        }
        return new AiModelConfig(provider, apiKey, baseUrl, model,
                thinkingType == null ? deepSeekProperties.getThinkingType() : thinkingType);
    }

}
