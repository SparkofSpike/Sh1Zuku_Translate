package com.shizuku.translate.service;

import com.shizuku.translate.dto.PixivMatchItem;
import com.shizuku.translate.dto.PixivMatchResponse;
import com.shizuku.translate.dto.PixivSearchItem;
import com.shizuku.translate.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Screenshot → work matching, server-side: runs the site's vision extraction and then the same
 * multi-search ranking the web import panel performs, so any API client (the QQ bridge, the
 * browser extension, future clients) resolves a screenshot to a work exactly like the website
 * does. The ranking rules themselves live in {@link PixivSearchRanking} and mirror the web
 * client's {@code pixivSearch.ts}; this service only plans the searches, runs them in parallel
 * and assembles the answer.
 *
 * <p>Plan, mirroring the web client: every one of the first few concrete tags gets its own
 * query (the decisive tag often sits further down the list and a single-tag query matches far
 * more reliably than an AND), the two strongest tags are ANDed as a precision probe, and the
 * full title plus its longest clean run are searched when the recognised title is genuinely a
 * title. Results merge by work id, and the highest-scoring candidate with a title match is
 * returned as {@code matched}; otherwise the caller receives the ranked short list.
 */
@Service
public class PixivMatchService {

    private static final Logger log = LoggerFactory.getLogger(PixivMatchService.class);

    /** Search rows kept per query (mirrors the web panel's per-call cap). */
    static final int SEARCH_RESULT_CAP = 20;

    /** Related candidates shown when no outright match was found. */
    static final int MAX_CANDIDATES = 20;

    /** Minimum score to count as "genuinely related": at least one shared concrete tag. */
    static final int MIN_RELEVANT_SCORE = 10;

    /** Maximum concurrent Pixiv searches per match request. */
    static final int MAX_PARALLEL_SEARCHES = 8;

    private final PixivNovelService pixivNovelService;
    private final PixivImageImportService pixivImageImportService;

    public PixivMatchService(PixivNovelService pixivNovelService,
                             PixivImageImportService pixivImageImportService) {
        this.pixivNovelService = pixivNovelService;
        this.pixivImageImportService = pixivImageImportService;
    }

    /** Runs vision extraction on the screenshots, then matches the recognised metadata. */
    public PixivMatchResponse matchFromImages(List<byte[]> images, List<String> mediaTypes) {
        Map<String, Object> extracted = pixivImageImportService.extractNovelInfo(images, mediaTypes);
        String title = extracted.get("title") instanceof String value ? value : "";
        List<String> tags = new ArrayList<>();
        if (extracted.get("tags") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String value && !value.isBlank()) {
                    tags.add(value);
                }
            }
        }
        return match(title, tags, extracted);
    }

    /**
     * Matches already-recognised metadata against Pixiv search results. Exposed for tests and
     * for callers that extracted the metadata elsewhere.
     */
    PixivMatchResponse match(String title, List<String> tags, Map<String, Object> extracted) {
        List<PlannedSearch> plans = planSearches(title, tags);
        if (plans.isEmpty()) {
            return new PixivMatchResponse(extracted, null, List.of(), List.of());
        }
        Map<String, MergeEntry> byId = runSearches(plans);
        List<ScoredItem> ranked = new ArrayList<>();
        for (MergeEntry entry : byId.values()) {
            ranked.add(new ScoredItem(entry.item,
                    PixivSearchRanking.scoreCandidate(title, tags, entry.item, entry.trusted)));
        }
        ranked.sort(Comparator.comparingInt(ScoredItem::score).reversed());

        PixivMatchItem matched = null;
        if (!ranked.isEmpty() && ranked.get(0).score() >= PixivSearchRanking.AUTO_IMPORT_SCORE) {
            matched = toMatchItem(ranked.get(0));
        }
        List<PixivMatchItem> candidates = new ArrayList<>();
        if (matched == null) {
            for (ScoredItem entry : ranked) {
                if (entry.score() < MIN_RELEVANT_SCORE) {
                    continue;
                }
                candidates.add(toMatchItem(entry));
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        }
        List<String> keywords = plans.stream().map(PlannedSearch::keyword).toList();
        return new PixivMatchResponse(extracted, matched, candidates, keywords);
    }

    /** Builds the parallel search plan exactly like the web import panel does. */
    private static List<PlannedSearch> planSearches(String title, List<String> tags) {
        List<String> concrete = PixivSearchRanking.concreteTags(tags);
        List<String> titleKeywords = PixivSearchRanking.isUsableTitle(title, tags)
                ? PixivSearchRanking.titleSearchKeywords(title)
                : List.of();
        List<PlannedSearch> searches = new ArrayList<>();
        for (String tag : concrete.subList(0, Math.min(4, concrete.size()))) {
            for (String keyword : PixivSearchRanking.tagSearchKeywords(List.of(tag))) {
                searches.add(new PlannedSearch(keyword, PixivSearchRanking.isTrustedKeyword(keyword)));
            }
        }
        if (concrete.size() >= 2) {
            for (String keyword : PixivSearchRanking.tagSearchKeywords(concrete.subList(0, 2))) {
                searches.add(new PlannedSearch(keyword, PixivSearchRanking.isTrustedKeyword(keyword)));
            }
        }
        for (String keyword : titleKeywords) {
            searches.add(new PlannedSearch(keyword, false));
        }
        return searches;
    }

    /**
     * Runs every planned search in parallel and merges the results by work id. A single failed
     * query only shrinks the pool of candidates; the request fails only when every query failed.
     */
    private Map<String, MergeEntry> runSearches(List<PlannedSearch> plans) {
        Map<String, MergeEntry> byId = new LinkedHashMap<>();
        int failures = 0;
        String firstError = null;
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(plans.size(), MAX_PARALLEL_SEARCHES));
        try {
            List<Future<SearchOutcome>> futures = new ArrayList<>();
            for (PlannedSearch plan : plans) {
                futures.add(executor.submit(() -> {
                    try {
                        List<PixivSearchItem> items = pixivNovelService.searchNovels(plan.keyword());
                        return new SearchOutcome(items.size() > SEARCH_RESULT_CAP
                                ? items.subList(0, SEARCH_RESULT_CAP) : items, null);
                    } catch (Exception e) {
                        return new SearchOutcome(List.of(), e.getMessage());
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                SearchOutcome outcome;
                try {
                    outcome = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcome = new SearchOutcome(List.of(), "搜索被中断");
                } catch (ExecutionException e) {
                    outcome = new SearchOutcome(List.of(), e.getCause() == null
                            ? e.getMessage() : e.getCause().getMessage());
                }
                if (outcome.error() != null) {
                    failures++;
                    if (firstError == null) {
                        firstError = outcome.error();
                    }
                    log.warn("Pixiv match search '{}' failed: {}", plans.get(i).keyword(), outcome.error());
                    continue;
                }
                for (PixivSearchItem item : outcome.items()) {
                    MergeEntry existing = byId.get(item.id());
                    if (existing == null) {
                        byId.put(item.id(), new MergeEntry(item, plans.get(i).trusted()));
                    } else if (plans.get(i).trusted()) {
                        existing.trusted = true;
                    }
                }
            }
        } finally {
            executor.shutdown();
        }
        if (failures == plans.size()) {
            throw new BusinessException(firstError != null ? firstError : "Pixiv 搜索失败，请稍后重试");
        }
        return byId;
    }

    private static PixivMatchItem toMatchItem(ScoredItem entry) {
        PixivSearchItem item = entry.item();
        return new PixivMatchItem(item.id(), item.title(), item.author(), item.tags(),
                item.xRestrict(), item.description(), item.textCount(), entry.score());
    }

    private record PlannedSearch(String keyword, boolean trusted) {
    }

    private record SearchOutcome(List<PixivSearchItem> items, String error) {
    }

    private record ScoredItem(PixivSearchItem item, int score) {
    }

    private static final class MergeEntry {
        final PixivSearchItem item;
        boolean trusted;

        MergeEntry(PixivSearchItem item, boolean trusted) {
            this.item = item;
            this.trusted = trusted;
        }
    }
}
