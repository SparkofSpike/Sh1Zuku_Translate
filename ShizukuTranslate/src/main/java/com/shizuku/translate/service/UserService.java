package com.shizuku.translate.service;

import com.shizuku.translate.config.DeepSeekConfig;
import com.shizuku.translate.dto.LoginRequest;
import com.shizuku.translate.dto.RegisterRequest;
import com.shizuku.translate.entity.AiModelProfile;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.integration.AiModelClient.AiModelConfig;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.AiModelProfileRepository;
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
    private final AiModelProfileRepository modelProfileRepository;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       DeepSeekConfig.DeepSeekProperties deepSeekProperties,
                       AiModelProfileRepository modelProfileRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.deepSeekProperties = deepSeekProperties;
        this.modelProfileRepository = modelProfileRepository;
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
        return maskApiKey(findByUsername(username).getAiApiKey());
    }

    public static String maskApiKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 11) return key.substring(0, Math.min(3, key.length())) + "*****";
        return key.substring(0, 7) + "*****" + key.substring(key.length() - 4);
    }

    @org.springframework.transaction.annotation.Transactional
    public java.util.List<AiModelProfile> listModelProfiles(String username) {
        User user = findByUsername(username);
        java.util.List<AiModelProfile> profiles = modelProfileRepository.findByUserIdOrderByCreatedAtAsc(user.getId());
        if (profiles.isEmpty() && hasLegacyModelConfig(user)) {
            profiles = java.util.List.of(createLegacyProfile(user));
        }
        return profiles;
    }

    @org.springframework.transaction.annotation.Transactional
    public AiModelProfile createModelProfile(String username, String name, String provider,
                                             String baseUrl, String model, String apiKey) {
        User user = findByUsername(username);
        ModelProfileValues values = normalizeModelProfile(name, provider, baseUrl, model, apiKey);
        AiModelProfile profile = AiModelProfile.builder()
                .user(user)
                .name(values.name)
                .provider(values.provider)
                .baseUrl(values.baseUrl)
                .model(values.model)
                .apiKey(values.apiKey)
                .build();
        return modelProfileRepository.save(profile);
    }

    @org.springframework.transaction.annotation.Transactional
    public AiModelProfile updateModelProfile(String username, Long profileId, String name, String provider,
                                             String baseUrl, String model, String apiKey, boolean clearApiKey) {
        User user = findByUsername(username);
        AiModelProfile profile = modelProfileRepository.findByIdAndUserId(profileId, user.getId())
                .orElseThrow(() -> new RuntimeException("Model profile not found"));
        String preservedKey = clearApiKey ? null : profile.getApiKey();
        ModelProfileValues values = normalizeModelProfile(name, provider, baseUrl, model,
                apiKey == null || apiKey.isBlank() ? preservedKey : apiKey);
        profile.setName(values.name);
        profile.setProvider(values.provider);
        profile.setBaseUrl(values.baseUrl);
        profile.setModel(values.model);
        profile.setApiKey(values.apiKey);
        return modelProfileRepository.save(profile);
    }

    @org.springframework.transaction.annotation.Transactional
    public void deleteModelProfile(String username, Long profileId) {
        User user = findByUsername(username);
        AiModelProfile profile = modelProfileRepository.findByIdAndUserId(profileId, user.getId())
                .orElseThrow(() -> new RuntimeException("Model profile not found"));
        modelProfileRepository.delete(profile);
    }

    private boolean hasLegacyModelConfig(User user) {
        return user.getAiApiKey() != null && !user.getAiApiKey().isBlank()
                || user.getAiModel() != null && !user.getAiModel().isBlank()
                || user.getAiBaseUrl() != null && !user.getAiBaseUrl().isBlank();
    }

    private AiModelProfile createLegacyProfile(User user) {
        String provider = user.getAiProvider() == null || user.getAiProvider().isBlank()
                ? "deepseek" : user.getAiProvider().toLowerCase();
        String model = user.getAiModel() == null || user.getAiModel().isBlank()
                ? deepSeekProperties.getDefaultModel() : user.getAiModel();
        ModelProfileValues values = normalizeModelProfile("旧模型配置", provider, user.getAiBaseUrl(), model, user.getAiApiKey());
        return modelProfileRepository.save(AiModelProfile.builder()
                .user(user).name(values.name).provider(values.provider).baseUrl(values.baseUrl)
                .model(values.model).apiKey(values.apiKey).build());
    }

    private ModelProfileValues normalizeModelProfile(String name, String provider, String baseUrl,
                                                      String model, String apiKey) {
        String normalizedProvider = provider == null || provider.isBlank()
                ? "deepseek" : provider.trim().toLowerCase();
        if (!normalizedProvider.equals("deepseek") && !normalizedProvider.equals("openai")
                && !normalizedProvider.equals("anthropic")) {
            throw new BusinessException("不支持的模型协议");
        }
        String normalizedBaseUrl = baseUrl == null || baseUrl.isBlank() ? defaultBaseUrl(normalizedProvider) : baseUrl.trim();
        String normalizedModel = model == null || model.isBlank()
                ? deepSeekProperties.getDefaultModel() : model.trim();
        String normalizedKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        if (!normalizedProvider.equals("deepseek") && normalizedKey == null) {
            throw new BusinessException("OpenAI 兼容和 Anthropic 兼容模型必须配置 API Key");
        }
        String normalizedName = name == null || name.isBlank()
                ? normalizedProvider + " / " + normalizedModel : name.trim();
        return new ModelProfileValues(normalizedName, normalizedProvider, normalizedBaseUrl, normalizedModel, normalizedKey);
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
        return resolveAiModelConfig(user, requestedModel, thinkingType, null);
    }

    public AiModelConfig resolveAiModelConfig(User user, String requestedModel, String thinkingType, Long modelProfileId) {
        if (Long.valueOf(0L).equals(modelProfileId)) {
            String model = requestedModel == null || requestedModel.isBlank()
                    ? deepSeekProperties.getDefaultModel() : requestedModel.trim();
            if (deepSeekProperties.getKey() == null || deepSeekProperties.getKey().isBlank()) {
                throw new BusinessException("站方 DeepSeek API Key 未配置");
            }
            return new AiModelConfig("deepseek", deepSeekProperties.getKey(), deepSeekProperties.getBaseUrl(), model,
                    thinkingType == null ? deepSeekProperties.getThinkingType() : thinkingType);
        }
        if (modelProfileId != null) {
            AiModelProfile profile = modelProfileRepository.findByIdAndUserId(modelProfileId, user.getId())
                    .orElseThrow(() -> new BusinessException("模型配置不存在或不属于当前用户"));
            String provider = profile.getProvider().toLowerCase();
            String apiKey = profile.getApiKey() == null || profile.getApiKey().isBlank()
                    ? ("deepseek".equals(provider) ? deepSeekProperties.getKey() : null) : profile.getApiKey().trim();
            String baseUrl = profile.getBaseUrl() == null || profile.getBaseUrl().isBlank()
                    ? defaultBaseUrl(provider) : profile.getBaseUrl().trim();
            if (apiKey == null || apiKey.isBlank()) {
                throw new BusinessException("该模型配置尚未配置 API Key");
            }
            return new AiModelConfig(provider, apiKey, baseUrl, profile.getModel(), thinkingType == null
                    ? deepSeekProperties.getThinkingType() : thinkingType);
        }

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

    private record ModelProfileValues(String name, String provider, String baseUrl, String model, String apiKey) {}
}
