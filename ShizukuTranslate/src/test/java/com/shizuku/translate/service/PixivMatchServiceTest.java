package com.shizuku.translate.service;

import com.shizuku.translate.dto.PixivMatchResponse;
import com.shizuku.translate.dto.PixivSearchItem;
import com.shizuku.translate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration behind the screenshot-match endpoint: search planning, parallel execution,
 * merge/dedupe behaviour and the matched-vs-candidates decision. The scoring rules themselves
 * are covered by {@link PixivSearchRankingTest}.
 */
class PixivMatchServiceTest {

    private PixivNovelService novelService;
    private PixivMatchService service;

    @BeforeEach
    void setUp() {
        novelService = Mockito.mock(PixivNovelService.class);
        service = new PixivMatchService(novelService, Mockito.mock(PixivImageImportService.class));
    }

    private static PixivSearchItem item(String id, String title, String... tags) {
        return new PixivSearchItem(id, title, "作者", List.of(tags), 0, "", 1000);
    }

    @Test
    void exactTitleMatchIsReturnedAsMatchedWithoutCandidates() {
        PixivSearchItem target = item("28032455", "私の幸せな日々", "BLACKSOULS", "BLACKSOULS2");
        when(novelService.searchNovels(anyString())).thenReturn(List.of(target));

        PixivMatchResponse response = service.match("私の幸せな日々",
                List.of("BLACKSOULS", "BLACKSOULS2"), Map.of());

        assertNotNull(response.matched());
        assertEquals("28032455", response.matched().id());
        assertTrue(response.matched().score() >= PixivSearchRanking.AUTO_IMPORT_SCORE);
        assertTrue(response.candidates().isEmpty());
    }

    @Test
    void relatedCandidatesAreListedWhenNoTitleMatchExists() {
        PixivSearchItem related = item("111", "別の作品", "BLACKSOULS");
        PixivSearchItem unrelated = item("222", "無関係", "ジャンルX");
        when(novelService.searchNovels(anyString())).thenReturn(List.of(related, unrelated));

        PixivMatchResponse response = service.match("", List.of("BLACKSOULS", "Gore"), Map.of());

        assertNull(response.matched());
        assertEquals(1, response.candidates().size());
        assertEquals("111", response.candidates().get(0).id());
    }

    @Test
    void mergeKeepsTrustedFlagForDuplicates() {
        PixivSearchItem target = item("28032455", "私の幸せな日々", "BLACKSOULS2");
        when(novelService.searchNovels(anyString())).thenReturn(List.of(target));

        // Search plan for one roman-digit tag: BLACKSOULS2 (trusted) and BLACKSOULSⅡ (untrusted).
        PixivMatchResponse response = service.match("", List.of("BLACKSOULSⅡ"), Map.of());

        assertEquals(1, response.candidates().size());
        // One digit-normalised tag overlap (10) plus the trusted-variant bonus (5).
        assertEquals(10 + PixivSearchRanking.PRECISE_VARIANT_BONUS, response.candidates().get(0).score());
    }

    @Test
    void plansTagAndTitleSearchesLikeTheWebPanel() {
        when(novelService.searchNovels(anyString())).thenReturn(List.of());

        service.match("私の幸せな日々",
                List.of("BLACKSOULS", "BLACKSOULS2", "紅ずきん", "小红帽", "追加タグ"), Map.of());

        // Each of the first four concrete tags gets its own query, the two strongest an AND probe.
        verify(novelService).searchNovels("BLACKSOULS");
        verify(novelService).searchNovels("BLACKSOULS2");
        verify(novelService).searchNovels("紅ずきん");
        verify(novelService).searchNovels("小红帽");
        verify(novelService).searchNovels("BLACKSOULS BLACKSOULS2");
        verify(novelService).searchNovels("私の幸せな日々");
        // The fifth concrete tag stays out of the plan.
        verify(novelService, never()).searchNovels("追加タグ");
    }

    @Test
    void partialSearchFailureStillReturnsResults() {
        PixivSearchItem target = item("28032455", "私の幸せな日々", "BLACKSOULS2");
        when(novelService.searchNovels("BLACKSOULS2")).thenReturn(List.of(target));
        when(novelService.searchNovels("BLACKSOULSⅡ")).thenThrow(new BusinessException("Pixiv 搜索失败"));

        PixivMatchResponse response = service.match("", List.of("BLACKSOULSⅡ"), Map.of());

        assertEquals(1, response.candidates().size());
        assertEquals("28032455", response.candidates().get(0).id());
    }

    @Test
    void allSearchesFailingFailsTheRequest() {
        when(novelService.searchNovels(anyString()))
                .thenThrow(new BusinessException("Pixiv 搜索失败（HTTP 500）"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.match("Title", List.of("Tag"), Map.of()));
        assertEquals("Pixiv 搜索失败（HTTP 500）", ex.getMessage());
    }

    @Test
    void noSearchableMetadataYieldsEmptyAnswer() {
        PixivMatchResponse response = service.match("", List.of(), Map.of("title", ""));

        assertNull(response.matched());
        assertTrue(response.candidates().isEmpty());
        assertTrue(response.keywords().isEmpty());
        Mockito.verifyNoInteractions(novelService);
    }
}
