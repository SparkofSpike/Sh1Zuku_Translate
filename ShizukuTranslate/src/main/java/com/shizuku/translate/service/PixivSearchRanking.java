package com.shizuku.translate.service;

import com.shizuku.translate.dto.PixivSearchItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure ranking helpers behind the screenshot-import search — the server-side mirror of the
 * web client's {@code src/utils/pixivSearch.ts}. The two implementations must stay in step:
 * the same screenshot must rank the same way whichever entry point (web panel or API client)
 * drives the search.
 *
 * <p>The recognised work is located without any user-selected search mode: several searches
 * run in parallel and the merged candidates are ranked by how well they match the recognised
 * metadata — a title match dominates, tag overlap breaks ties.
 */
final class PixivSearchRanking {

    /** The candidate's title equals the recognised one outright. */
    static final int TITLE_EXACT_SCORE = 1200;

    /** The candidate's title contains (or is contained by) the recognised one. */
    static final int TITLE_MATCH_SCORE = 1000;

    /** A candidate scoring at least this much carries a title match: import it directly. */
    static final int AUTO_IMPORT_SCORE = 1000;

    /** Candidates reached through a trusted keyword get this small ranking bonus. */
    static final int PRECISE_VARIANT_BONUS = 5;

    /**
     * Catch-all tags that only dilute a tag search: R-18 alone matches tens of thousands of
     * works, so ANDing it either zeroes the result (the target may not carry it) or floods the
     * candidate list. Concrete tags are what actually locate a work.
     */
    private static final Set<String> GENERIC_TAGS = Set.of(
            "r-18", "r18", "r-18g", "r18g", "成人向け", "全年齢", "r指定",
            "オリジナル", "二次創作", "短編", "長編", "連載", "完結", "シリーズ",
            "小説", "novel", "漫画", "イラスト", "fanart", "その他");

    /** Roman numerals and full-width digits map to plain ASCII digits. */
    private static final Map<String, String> ROMAN_DIGITS = Map.ofEntries(
            Map.entry("Ⅰ", "1"), Map.entry("Ⅱ", "2"), Map.entry("Ⅲ", "3"), Map.entry("Ⅳ", "4"),
            Map.entry("Ⅴ", "5"), Map.entry("Ⅵ", "6"), Map.entry("Ⅶ", "7"), Map.entry("Ⅷ", "8"),
            Map.entry("Ⅸ", "9"), Map.entry("Ⅹ", "10"),
            Map.entry("ⅰ", "1"), Map.entry("ⅱ", "2"), Map.entry("ⅲ", "3"), Map.entry("ⅳ", "4"),
            Map.entry("ⅴ", "5"), Map.entry("ⅵ", "6"), Map.entry("ⅶ", "7"), Map.entry("ⅷ", "8"),
            Map.entry("ⅸ", "9"), Map.entry("ⅹ", "10"));

    /**
     * Java's {@code \s} is narrower than JavaScript's — it misses \u3000 and friends — so the
     * whitespace class is spelled out to keep title normalisation identical across the two.
     */
    private static final String SPACE =
            "\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";

    /** Separators that split a title into searchable runs (mirrors the TS character class). */
    private static final Pattern TITLE_SPLIT = Pattern.compile(
            "[" + SPACE + "、。，,.!！?？…·:：;；\\-—ー～~「」『』【】()（）\\[\\]]+");

    /** Characters removed before tolerant title comparison (mirrors the TS character class). */
    private static final Pattern TITLE_NOISE = Pattern.compile(
            "[" + SPACE + "、。，,.!！?？…·:：;；\\-—ー～~「」『』【】()（）\\[\\]・／/｜|#]");

    /** CJK runs are information-dense: two characters already identify a title. */
    private static final Pattern CJK = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");

    private PixivSearchRanking() {
    }

    /** Longest punctuation-free run of the title — the best keyword for title-mode search. */
    static String titleSearchChunk(String title) {
        String value = title == null ? "" : title;
        List<String> parts = new ArrayList<>();
        for (String part : TITLE_SPLIT.split(value)) {
            String trimmed = part.trim();
            if (trimmed.length() >= 2) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty()) {
            String trimmed = value.trim();
            return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20);
        }
        String longest = parts.get(0);
        for (String part : parts) {
            if (part.length() > longest.length()) {
                longest = part;
            }
        }
        return longest;
    }

    /**
     * Keywords used to search for a recognised title. The full title goes first — Pixiv's
     * default novel search matches it as-is, spaces and all — and the longest clean run is
     * kept as a second attempt for titles wrapped in heavy decoration.
     */
    static List<String> titleSearchKeywords(String title) {
        String full = title == null ? "" : title.trim();
        if (full.isEmpty()) {
            return List.of();
        }
        String chunk = titleSearchChunk(full);
        if (!chunk.isEmpty() && !chunk.equals(full)) {
            return List.of(full, chunk);
        }
        return List.of(full);
    }

    /** Lower-cased title with spaces and punctuation removed, for tolerant comparison. */
    static String normalizeTitle(String value) {
        if (value == null) {
            return "";
        }
        return TITLE_NOISE.matcher(value.toLowerCase()).replaceAll("");
    }

    /** True when one title contains the other after normalisation, and both sides are distinctive. */
    static boolean titleMatches(String a, String b) {
        String na = normalizeTitle(a);
        String nb = normalizeTitle(b);
        if (!isDistinctiveRun(na) || !isDistinctiveRun(nb)) {
            return false;
        }
        return na.contains(nb) || nb.contains(na);
    }

    /** Distinguishing power of a normalised run: >=2 CJK characters, or >=4 Latin ones. */
    static boolean isDistinctiveRun(String run) {
        if (run == null || run.isEmpty()) {
            return false;
        }
        if (CJK.matcher(run).find()) {
            return run.length() >= 2;
        }
        return run.length() >= 4;
    }

    /** Tag comparison is case-insensitive. */
    static String normalizeTag(String tag) {
        return tag == null ? "" : tag.trim().toLowerCase();
    }

    /**
     * Digit normalisation for search keywords: the vision model unpredictably transcribes a tag
     * like {@code BLACKSOULS2} as {@code BLACKSOULSⅡ}, and Pixiv treats the roman form as a
     * fuzzy query returning unrelated works — the transcription alone can make the original
     * work unfindable. Converting to ASCII digits before searching fixes that.
     */
    static String normalizeTagForSearch(String tag) {
        String text = tag == null ? "" : tag;
        for (Map.Entry<String, String> entry : ROMAN_DIGITS.entrySet()) {
            if (text.contains(entry.getKey())) {
                text = text.replace(entry.getKey(), entry.getValue());
            }
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '０' && ch <= '９') {
                out.append((char) ('0' + (ch - '０')));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** Tags worth searching with: concrete tags when any exist, otherwise the full list. */
    static List<String> concreteTags(List<String> tags) {
        List<String> cleaned = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null) {
                    continue;
                }
                String value = tag.trim();
                if (!value.isEmpty()) {
                    cleaned.add(value);
                }
            }
        }
        List<String> concrete = cleaned.stream()
                .filter(tag -> !GENERIC_TAGS.contains(tag.toLowerCase()))
                .toList();
        return concrete.isEmpty() ? cleaned : concrete;
    }

    /**
     * Keywords to try for a set of tags. When the digits differ, the digit-normalised form comes
     * first: the vision model's roman/full-width digits are the suspect transcription, so the
     * ASCII form is the more trustworthy query (and its hits rank higher in the merge).
     */
    static List<String> tagSearchKeywords(List<String> tags) {
        String joined = tags == null ? "" : String.join(" ", tags).trim();
        if (joined.isEmpty()) {
            return List.of();
        }
        String normalized = normalizeTagForSearch(joined);
        if (!normalized.equals(joined)) {
            return List.of(normalized, joined);
        }
        return List.of(joined);
    }

    /**
     * Whether a search keyword is already in its trusted (digit-normalised) form. Hits from such
     * queries rank slightly higher: a query that needed digit correction may have matched
     * Pixiv's fuzzy search, and works merely *tagged* with the roman form are not what the
     * user's screenshot actually showed.
     */
    static boolean isTrustedKeyword(String keyword) {
        return keyword != null && !keyword.isEmpty() && normalizeTagForSearch(keyword).equals(keyword);
    }

    /**
     * Whether the recognised title is worth a title-mode search. A "title" that just repeats one
     * of the tags (the model sometimes promotes a tag to the title) or has no distinctive run
     * would only pull in unrelated works — "no distinctive run" is a low bar for CJK, where two
     * characters already make a title.
     */
    static boolean isUsableTitle(String title, List<String> tags) {
        String normalized = normalizeTitle(title);
        if (!isDistinctiveRun(normalized)) {
            return false;
        }
        if (tags != null) {
            for (String tag : tags) {
                if (normalizeTitle(tag).equals(normalized)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Relevance of one candidate against the recognised metadata. The title dominates and the
     * count of recognised *concrete* tags the candidate also carries breaks ties below that.
     * Generic catch-alls (R-18 etc.) are excluded from scoring. Both sides are digit-normalised
     * so a tag read as BLACKSOULSⅡ still matches a work tagged BLACKSOULS2.
     *
     * @param trusted true when the candidate was reached through a digit-normalised (trusted)
     *                keyword; such hits get a small bonus over fuzzy-search neighbours.
     */
    static int scoreCandidate(String wantedTitleRaw, List<String> wantedTagsRaw,
                              PixivSearchItem item, boolean trusted) {
        String wantedTitle = normalizeTitle(wantedTitleRaw);
        String candidateTitle = normalizeTitle(item.title());
        int titleScore = 0;
        if (!wantedTitle.isEmpty() && wantedTitle.equals(candidateTitle)) {
            titleScore = TITLE_EXACT_SCORE;
        } else if (titleMatches(wantedTitleRaw, item.title())) {
            titleScore = TITLE_MATCH_SCORE;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String tag : concreteTags(wantedTagsRaw)) {
            String key = normalizeTagForSearch(normalizeTag(tag));
            if (!key.isEmpty()) {
                wanted.add(key);
            }
        }
        Set<String> present = new HashSet<>();
        for (String tag : item.tags()) {
            present.add(normalizeTagForSearch(normalizeTag(tag)));
        }
        int overlap = 0;
        for (String tag : wanted) {
            if (present.contains(tag)) {
                overlap++;
            }
        }
        return titleScore + Math.min(overlap, 9) * 10 + (trusted ? PRECISE_VARIANT_BONUS : 0);
    }
}
