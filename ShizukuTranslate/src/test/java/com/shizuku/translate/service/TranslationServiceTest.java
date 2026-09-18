package com.shizuku.translate.service;

import com.shizuku.translate.dto.TranslateRequest;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.entity.Preset;
import com.shizuku.translate.entity.TranslationRecord;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.integration.AiModelClient;
import com.shizuku.translate.integration.AiModelClient.AiModelConfig;
import com.shizuku.translate.integration.AiModelClient.DeepSeekResult;
import com.shizuku.translate.repository.PresetRepository;
import com.shizuku.translate.repository.TranslationCacheRepository;
import com.shizuku.translate.repository.TranslationRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the non-streaming translation path and the system prompt assembly:
 * prompt ordering (base -> language notes -> presets -> custom), preset rendering from
 * the database, and history ownership.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranslationServiceTest {

    @Mock
    private AiModelClient aiModelClient;

    @Mock
    private TranslationRecordRepository recordRepository;

    @Mock
    private TranslationCacheRepository cacheRepository;

    @Mock
    private UserService userService;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private UsageService usageService;

    @Mock
    private TranslationResultWriter resultWriter;

    @Mock
    private PresetRepository presetRepository;

    @InjectMocks
    private TranslationService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setUsername("alice");
        // The real PromptTemplateService reads preset rows from the database,
        // so point the mocked prompt builder at the real implementation with
        // the shared mocks injected.
        promptTemplateService = new PromptTemplateService(new AppConfigStub(), new GlossaryService(null), presetRepository);
        service = new TranslationService(aiModelClient, recordRepository, cacheRepository,
                userService, promptTemplateService, usageService, resultWriter);
        lenient().when(presetRepository.findAll()).thenReturn(List.of(
                Preset.builder().id(1L).name("NSFW破甲").prompt("preset-rule").build()));
        // TranslationService looks the user up by name before every path.
        lenient().when(userService.findByUsername("alice")).thenReturn(user);
    }

    /** Only the target-language pieces the real prompt builder touches. */
    private static class AppConfigStub extends com.shizuku.translate.config.AppConfig.AppProperties {
        @Override
        public String resolveTargetLanguage(String requested) {
            return "zh-CN";
        }

        @Override
        public String targetLanguageLabel(String code) {
            return "简体中文";
        }
    }

    private static TranslateRequest request(String sourceText, List<String> presets, String customPrompt) {
        TranslateRequest request = new TranslateRequest();
        request.setSourceText(sourceText);
        request.setPresets(presets);
        request.setCustomPrompt(customPrompt);
        return request;
    }

    @Test
    void nonStreamingTranslationPersistsResultThroughTheWriter() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        when(aiModelClient.chat(anyString(), anyString(), any(AiModelConfig.class)))
                .thenReturn(new DeepSeekResult("译文", null));

        service.translate("alice", request("原文", null, null), false);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelClient).chat(promptCaptor.capture(), eq("原文"), any(AiModelConfig.class));
        assertTrue(promptCaptor.getValue().contains("简体中文"));
        // Fresh model translations must carry the resolved target language so they can be
        // found by other users' shared-translation lookups.
        verify(resultWriter).persistTranslate(eq(user), any(AiModelConfig.class), any(), eq("译文"), eq("原文"), eq(null), eq("zh-CN"));
    }

    @Test
    void presetPromptFromDatabaseIsInjectedIntoTheSystemPrompt() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        when(aiModelClient.chat(anyString(), anyString(), any(AiModelConfig.class)))
                .thenReturn(new DeepSeekResult("译文", null));

        service.translate("alice", request("原文", List.of("NSFW破甲"), null), false);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelClient).chat(promptCaptor.capture(), anyString(), any(AiModelConfig.class));
        assertTrue(promptCaptor.getValue().contains("请特别注意以下要求："));
        assertTrue(promptCaptor.getValue().contains("preset-rule"));
    }

    @Test
    void unknownPresetKeepsItsRawNameInsteadOfSilentlyDisappearing() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        when(aiModelClient.chat(anyString(), anyString(), any(AiModelConfig.class)))
                .thenReturn(new DeepSeekResult("译文", null));

        service.translate("alice", request("原文", List.of("ghost-preset"), null), false);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelClient).chat(promptCaptor.capture(), anyString(), any(AiModelConfig.class));
        assertTrue(promptCaptor.getValue().contains("ghost-preset"));
    }

    @Test
    void blankSourceTextIsRejectedBeforeAnyModelCall() {
        try {
            service.translate("alice", request("   ", null, null), false);
            org.junit.jupiter.api.Assertions.fail("expected BusinessException");
        } catch (com.shizuku.translate.exception.BusinessException expected) {
            // fall through
        }
        verify(aiModelClient, never()).chat(anyString(), anyString(), any(AiModelConfig.class));
    }

    @Test
    void historyDetailIsOnlyVisibleToItsOwner() {
        TranslationRecord record = new TranslationRecord();
        record.setId(5L);
        record.setSourceText("原文");
        when(recordRepository.findByIdAndUserId(5L, 1L)).thenReturn(java.util.Optional.of(record));
        when(recordRepository.findByIdAndUserId(6L, 1L)).thenReturn(java.util.Optional.empty());

        assertEquals(5L, service.getDetail(5L, "alice").getId());
        try {
            service.getDetail(6L, "alice");
            org.junit.jupiter.api.Assertions.fail("expected ResourceNotFoundException");
        } catch (com.shizuku.translate.exception.ResourceNotFoundException expected) {
            // fall through
        }
    }

    @Test
    void streamingCacheHitSkipsTheModelAndStillPersistsHistory() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        // Older rows may miss per-field counts; the replay must not crash on them.
        com.shizuku.translate.entity.TranslationCache cached =
                com.shizuku.translate.entity.TranslationCache.builder()
                        .userId(1L)
                        .cacheKey("k")
                        .model("m")
                        .translatedText("缓存译文")
                        .totalTokens(10)
                        .build();
        when(cacheRepository.findByUserIdAndCacheKeyOrderByCreatedAtDesc(any(), anyString()))
                .thenReturn(List.of(cached));
        when(recordRepository.save(any(TranslationRecord.class))).thenAnswer(inv -> {
            TranslationRecord rec = inv.getArgument(0);
            rec.setId(9L);
            return rec;
        });

        List<String> tokens = new java.util.ArrayList<>();
        List<TranslateResponse> done = new java.util.ArrayList<>();
        service.translateStream("alice", request("原文", null, null), false,
                tokens::add, done::add, error -> { }, () -> { });

        assertEquals(List.of("缓存译文"), tokens);
        assertEquals("缓存译文", done.get(0).getTranslatedText());
        assertEquals(10, done.get(0).getTokenUsage().getTotalTokens());
        verify(aiModelClient, never()).chatStream(anyString(), anyString(), any(AiModelConfig.class),
                any(), any(), any(), any(), any());
        verify(recordRepository).save(any(TranslationRecord.class));
    }

    @Test
    void streamingTokensAccumulateAndCompleteWithTheFullText() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        when(cacheRepository.findByUserIdAndCacheKeyOrderByCreatedAtDesc(any(), anyString()))
                .thenReturn(List.of());
        when(recordRepository.save(any(TranslationRecord.class))).thenAnswer(inv -> {
            TranslationRecord rec = inv.getArgument(0);
            rec.setId(9L);
            return rec;
        });

        // Replay a token stream, then complete with the accumulated usage.
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            Consumer<com.shizuku.translate.dto.TokenUsage> onComplete = invocation.getArgument(4);
            onToken.accept("你");
            onToken.accept("好");
            onComplete.accept(new com.shizuku.translate.dto.TokenUsage());
            return null;
        }).when(aiModelClient).chatStream(anyString(), anyString(), any(AiModelConfig.class),
                any(), any(), any(), any(), any());

        List<String> tokens = new java.util.ArrayList<>();
        List<TranslateResponse> done = new java.util.ArrayList<>();
        service.translateStream("alice", request("原文", null, null), false,
                tokens::add, done::add, error -> { }, () -> { });

        assertEquals(List.of("你", "好"), tokens);
        assertEquals("你好", done.get(0).getTranslatedText());
        // The completed translation is cached for the next identical request.
        verify(cacheRepository).save(any(com.shizuku.translate.entity.TranslationCache.class));
    }

    private static TranslationRecord sharedRecord(Long id, String text) {
        TranslationRecord rec = TranslationRecord.builder()
                .id(id)
                .sourceText("原文")
                .translatedText(text)
                .model("别人的模型")
                .targetLanguage("zh-CN")
                .build();
        rec.setCreatedAt(java.time.LocalDateTime.now());
        return rec;
    }

    @Test
    void streamingSharedTranslationIsReplayedInsteadOfCallingTheModel() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        when(recordRepository.findFirstByUserIdNotAndSourceTextAndTargetLanguageOrderByCreatedAtDesc(1L, "原文", "zh-CN"))
                .thenReturn(java.util.Optional.of(sharedRecord(7L, "别人的译文")));
        when(recordRepository.save(any(TranslationRecord.class))).thenAnswer(inv -> {
            TranslationRecord rec = inv.getArgument(0);
            rec.setId(9L);
            return rec;
        });

        List<String> tokens = new java.util.ArrayList<>();
        List<TranslateResponse> done = new java.util.ArrayList<>();
        service.translateStream("alice", request("原文", null, null), false,
                tokens::add, done::add, error -> { }, () -> { });

        assertEquals(List.of("别人的译文"), tokens);
        assertEquals("别人的译文", done.get(0).getTranslatedText());
        assertTrue(done.get(0).isFromSharedTranslation());
        verify(aiModelClient, never()).chatStream(anyString(), anyString(), any(AiModelConfig.class),
                any(), any(), any(), any(), any());
        // The reused translation still lands in the caller's own history.
        ArgumentCaptor<TranslationRecord> saved = ArgumentCaptor.forClass(TranslationRecord.class);
        verify(recordRepository).save(saved.capture());
        assertEquals("zh-CN", saved.getValue().getTargetLanguage());
        // The personal cache is not consulted once a shared result was served.
        verify(cacheRepository, never()).findByUserIdAndCacheKeyOrderByCreatedAtDesc(any(), anyString());
    }

    @Test
    void skipCacheBypassesSharedTranslationsAndForcesAFreshModelCall() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        when(cacheRepository.findByUserIdAndCacheKeyOrderByCreatedAtDesc(any(), anyString()))
                .thenReturn(List.of());
        when(recordRepository.save(any(TranslationRecord.class))).thenAnswer(inv -> {
            TranslationRecord rec = inv.getArgument(0);
            rec.setId(9L);
            return rec;
        });
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            Consumer<com.shizuku.translate.dto.TokenUsage> onComplete = invocation.getArgument(4);
            onToken.accept("新译文");
            onComplete.accept(new com.shizuku.translate.dto.TokenUsage());
            return null;
        }).when(aiModelClient).chatStream(anyString(), anyString(), any(AiModelConfig.class),
                any(), any(), any(), any(), any());

        TranslateRequest req = request("原文", null, null);
        req.setSkipCache(true);
        List<String> tokens = new java.util.ArrayList<>();
        List<TranslateResponse> done = new java.util.ArrayList<>();
        service.translateStream("alice", req, false,
                tokens::add, done::add, error -> { }, () -> { });

        assertEquals(List.of("新译文"), tokens);
        assertTrue(done.get(0).isFromSharedTranslation() == false);
        assertTrue(done.get(0).isFromCache() == false);
        // Neither reuse path may even be queried.
        verify(recordRepository, never())
                .findFirstByUserIdNotAndSourceTextAndTargetLanguageOrderByCreatedAtDesc(any(), any(), any());
        verify(cacheRepository, never()).findByUserIdAndCacheKeyOrderByCreatedAtDesc(any(), anyString());
    }

    @Test
    void nonStreamingSharedTranslationIsServedWithoutAModelCall() {
        when(recordRepository.findFirstByUserIdNotAndSourceTextAndTargetLanguageOrderByCreatedAtDesc(1L, "原文", "zh-CN"))
                .thenReturn(java.util.Optional.of(sharedRecord(7L, "别人的译文")));

        TranslateResponse response = service.translate("alice", request("原文", null, null), false);

        assertEquals("别人的译文", response.getTranslatedText());
        assertTrue(response.isFromSharedTranslation());
        verify(aiModelClient, never()).chat(anyString(), anyString(), any(AiModelConfig.class));
    }

    @Test
    void streamingNonSharedResultIsTaggedAsCacheAndNotShared() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        when(cacheRepository.findByUserIdAndCacheKeyOrderByCreatedAtDesc(any(), anyString()))
                .thenReturn(List.of());
        when(recordRepository.save(any(TranslationRecord.class))).thenAnswer(inv -> {
            TranslationRecord rec = inv.getArgument(0);
            rec.setId(9L);
            return rec;
        });
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            Consumer<com.shizuku.translate.dto.TokenUsage> onComplete = invocation.getArgument(4);
            onToken.accept("新译文");
            onComplete.accept(new com.shizuku.translate.dto.TokenUsage());
            return null;
        }).when(aiModelClient).chatStream(anyString(), anyString(), any(AiModelConfig.class),
                any(), any(), any(), any(), any());

        List<TranslateResponse> done = new java.util.ArrayList<>();
        service.translateStream("alice", request("原文", null, null), false,
                t -> { }, done::add, error -> { }, () -> { });

        assertTrue(done.get(0).isFromSharedTranslation() == false);
        assertTrue(done.get(0).isFromCache() == false);
    }

    @Test
    void streamingCacheHitIsTaggedFromCache() {
        when(userService.resolveAiModelConfig(eq(user), any(), any(), any()))
                .thenReturn(new AiModelConfig("deepseek", "key", "https://base", "m", "disabled"));
        com.shizuku.translate.entity.TranslationCache cached =
                com.shizuku.translate.entity.TranslationCache.builder()
                        .userId(1L)
                        .cacheKey("k")
                        .model("m")
                        .translatedText("缓存译文")
                        .totalTokens(10)
                        .build();
        when(cacheRepository.findByUserIdAndCacheKeyOrderByCreatedAtDesc(any(), anyString()))
                .thenReturn(List.of(cached));
        when(recordRepository.save(any(TranslationRecord.class))).thenAnswer(inv -> {
            TranslationRecord rec = inv.getArgument(0);
            rec.setId(9L);
            return rec;
        });

        List<TranslateResponse> done = new java.util.ArrayList<>();
        service.translateStream("alice", request("原文", null, null), false,
                t -> { }, done::add, error -> { }, () -> { });

        assertTrue(done.get(0).isFromCache());
        assertTrue(done.get(0).isFromSharedTranslation() == false);
    }
}
