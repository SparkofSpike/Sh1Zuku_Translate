package com.shizuku.translate.service;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.config.DeepSeekConfig;
import com.shizuku.translate.dto.LoginRequest;
import com.shizuku.translate.dto.RegisterRequest;
import com.shizuku.translate.entity.AiModelProfile;
import com.shizuku.translate.entity.PersonalModelApiKey;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.exception.EmailNotVerifiedException;
import com.shizuku.translate.integration.AiModelClient.AiModelConfig;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.AiModelProfileRepository;
import com.shizuku.translate.repository.UserRepository;
import com.shizuku.translate.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final DeepSeekConfig.DeepSeekProperties deepSeekProperties;
    private static final String LEGACY_PROFILE_NAME = "旧模型配置";

    private final AiModelProfileRepository modelProfileRepository;
    private final com.shizuku.translate.repository.PersonalModelApiKeyRepository personalModelApiKeyRepository;
    private final ObjectMapper objectMapper;
    private final EmailVerificationService emailVerificationService;
    private final AppConfig.AppProperties appProperties;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       DeepSeekConfig.DeepSeekProperties deepSeekProperties,
                       AiModelProfileRepository modelProfileRepository,
                       com.shizuku.translate.repository.PersonalModelApiKeyRepository personalModelApiKeyRepository,
                       ObjectMapper objectMapper,
                       EmailVerificationService emailVerificationService,
                       AppConfig.AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.deepSeekProperties = deepSeekProperties;
        this.modelProfileRepository = modelProfileRepository;
        this.personalModelApiKeyRepository = personalModelApiKeyRepository;
        this.objectMapper = objectMapper;
        this.emailVerificationService = emailVerificationService;
        this.appProperties = appProperties;
    }

    public void register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new RuntimeException("Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered");
        }
        // Registration is only allowed with a code freshly sent to this address,
        // which is what stops fake-mailbox (e.g. abcde@qq.com) registrations.
        emailVerificationService.verify(email, request.getCode());
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    /**
     * Verify (or change and verify) the logged-in user's account email.
     * Existing accounts created before email verification are unverified:
     * verifying the current address unlocks the account; verifying a new
     * address also updates it.
     */
    @org.springframework.transaction.annotation.Transactional
    public void verifyEmail(String username, String newEmail, String code) {
        User user = findByUsername(username);
        String email = newEmail.trim().toLowerCase(Locale.ROOT);
        boolean changed = !email.equalsIgnoreCase(user.getEmail());
        if (changed && userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("该邮箱已被其他账号使用");
        }
        emailVerificationService.verify(email, code);
        user.setEmail(email);
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    /** Whether the account may consume paid features. Admins are always exempt. */
    public boolean isEmailVerified(String username) {
        if (appProperties.isAdmin(username)) {
            return true;
        }
        User user = findByUsername(username);
        return Boolean.TRUE.equals(user.getEmailVerified());
    }

    /**
     * Gate for paid features (translation endpoints, extension API-key
     * creation). Throws {@link EmailNotVerifiedException} (HTTP 403) when
     * the account has not verified its email. Admins are exempt.
     */
    public void requireEmailVerified(String username) {
        if (!isEmailVerified(username)) {
            throw new EmailNotVerifiedException("邮箱尚未认证，请先在「个人」页面完成邮箱认证后再使用翻译功能");
        }
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
        if (hasLegacyModelConfig(user)
                && profiles.stream().noneMatch(profile -> LEGACY_PROFILE_NAME.equals(profile.getName()))) {
            profiles = new ArrayList<>(profiles);
            profiles.add(createLegacyProfile(user));
        }
        return profiles;
    }

    @org.springframework.transaction.annotation.Transactional
    public AiModelProfile createModelProfile(String username, String name, String provider,
                                             String baseUrl, String model, String apiKey) {
        User user = findByUsername(username);
        ModelProfileValues values = normalizeModelProfile(name, provider, baseUrl, model, apiKey);
        PersonalModelApiKey credential = credentialFor(user, values, apiKey);
        AiModelProfile profile = AiModelProfile.builder()
                .user(user).name(values.name).provider(values.provider).baseUrl(values.baseUrl)
                .model(values.model).models(writeModels(List.of(values.model))).apiKey(null).personalModelApiKey(credential).build();
        return modelProfileRepository.save(profile);
    }

    public void setModelList(AiModelProfile profile, List<String> models) {
        try { profile.setModels(objectMapper.writeValueAsString(models)); }
        catch (JsonProcessingException e) { throw new BusinessException("无法保存模型列表"); }
    }

    private String writeModels(List<String> models) {
        try { return objectMapper.writeValueAsString(models); }
        catch (JsonProcessingException e) { throw new BusinessException("无法保存模型列表"); }
    }

    @org.springframework.transaction.annotation.Transactional
    public java.util.List<AiModelProfile> updateModelProfiles(String username, Long profileId, String name, String provider,
                                                               String baseUrl, java.util.List<String> models,
                                                               String apiKey, boolean clearApiKey) {
        User user = findByUsername(username);
        AiModelProfile anchor = modelProfileRepository.findByIdAndUserId(profileId, user.getId())
                .orElseThrow(() -> new RuntimeException("Model profile not found"));
        java.util.List<AiModelProfile> group = java.util.List.of(anchor);
        /* Existing split rows remain compatible, but new edits stay on the anchor row. */
        /*
        group = modelProfileRepository.findByUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .filter(item -> java.util.Objects.equals(item.getName(), anchor.getName())
                        && java.util.Objects.equals(item.getProvider(), anchor.getProvider())
                        && java.util.Objects.equals(item.getBaseUrl(), anchor.getBaseUrl()))
                .toList();
        */
        String preservedKey = clearApiKey ? null : effectiveApiKey(anchor);
        String effectiveKey = apiKey == null || apiKey.isBlank() ? preservedKey : apiKey;
        java.util.List<String> normalizedModels = models == null ? java.util.List.of() : models.stream()
                .map(item -> item == null ? "" : item.trim()).filter(item -> !item.isBlank()).distinct().toList();
        if (normalizedModels.isEmpty()) throw new BusinessException("请至少添加一个模型名称");
        ModelProfileValues values = normalizeModelProfile(name, provider, baseUrl, normalizedModels.get(0), effectiveKey);
        anchor.setName(values.name);
        anchor.setProvider(values.provider);
        anchor.setBaseUrl(values.baseUrl);
        anchor.setModel(normalizedModels.get(0));
        anchor.setModels(writeModels(normalizedModels));
        anchor.setApiKey(null);
        anchor.setPersonalModelApiKey(clearApiKey && (apiKey == null || apiKey.isBlank())
                ? null : credentialFor(user, values, effectiveKey));
        return java.util.List.of(modelProfileRepository.save(anchor));
    }

    @org.springframework.transaction.annotation.Transactional
    public AiModelProfile updateModelProfile(String username, Long profileId, String name, String provider,
                                             String baseUrl, String model, String apiKey, boolean clearApiKey) {
        return updateModelProfiles(username, profileId, name, provider, baseUrl,
                java.util.List.of(model), apiKey, clearApiKey).get(0);
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
        ModelProfileValues values = normalizeModelProfile(LEGACY_PROFILE_NAME, provider, user.getAiBaseUrl(), model, user.getAiApiKey());
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
            String storedKey = effectiveApiKey(profile);
            String apiKey = storedKey == null || storedKey.isBlank()
                    ? ("deepseek".equals(provider) ? deepSeekProperties.getKey() : null) : storedKey.trim();
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

    private String effectiveApiKey(AiModelProfile profile) {
        if (profile.getPersonalModelApiKey() != null) return profile.getPersonalModelApiKey().getApiKey();
        return profile.getApiKey();
    }

    private PersonalModelApiKey credentialFor(User user, ModelProfileValues values, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        return personalModelApiKeyRepository.findByUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .filter(item -> item.getProvider().equals(values.provider)
                        && item.getApiKey().equals(apiKey.trim()))
                .findFirst()
                .orElseGet(() -> personalModelApiKeyRepository.save(
                        PersonalModelApiKey.builder().user(user).name(values.name + " Key")
                                .provider(values.provider).apiKey(apiKey.trim()).baseUrl(values.baseUrl).build()));
    }

    public java.util.List<String> detectModels(String username, Long profileId, String provider, String baseUrl, String apiKey) {
        // When editing an existing profile the browser does not resend the stored
        // key, so fall back to the credential already saved on that profile.
        if ((apiKey == null || apiKey.isBlank()) && profileId != null) {
            User user = findByUsername(username);
            AiModelProfile profile = modelProfileRepository.findByIdAndUserId(profileId, user.getId())
                    .orElseThrow(() -> new BusinessException("模型配置不存在"));
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = profile.getBaseUrl();
            if (provider == null || provider.isBlank()) provider = profile.getProvider();
            apiKey = effectiveApiKey(profile);
        }
        if (apiKey == null || apiKey.isBlank()) throw new BusinessException("请先配置 API Key");
        String normalizedProvider = provider == null || provider.isBlank() ? "deepseek" : provider.trim().toLowerCase();
        String url = baseUrl == null || baseUrl.isBlank() ? defaultBaseUrl(normalizedProvider) : baseUrl.trim();
        try {
            java.util.Map<?, ?> response = org.springframework.web.client.RestClient.builder()
                    .baseUrl(url.replaceAll("/+$", ""))
                    .defaultHeader("Authorization", "Bearer " + apiKey.trim())
                    .build().get().uri("/models").retrieve().body(java.util.Map.class);
            Object data = response == null ? null : response.get("data");
            if (!(data instanceof java.util.List<?> list)) return java.util.List.of();
            return list.stream().filter(item -> item instanceof java.util.Map<?, ?>)
                    .map(item -> String.valueOf(((java.util.Map<?, ?>) item).get("id")))
                    .filter(item -> !"null".equals(item)).sorted().toList();
        } catch (RuntimeException ex) {
            throw new BusinessException("模型列表检测失败：" + (ex.getMessage() == null ? "供应商未提供 /models" : ex.getMessage()));
        }
    }

    private record ModelProfileValues(String name, String provider, String baseUrl, String model, String apiKey) {}
}
