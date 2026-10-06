package com.shizuku.translate.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of the screenshot-match endpoint: the recognised metadata, the work that was
 * identified outright (title match) when there is one, and the ranked short list of genuinely
 * related candidates otherwise.
 *
 * @param extracted  the vision model's reading of the screenshot ({@code title}, {@code author},
 *                   {@code tags}, {@code summary}); never null
 * @param matched    the work identified with a title match — a client can import it directly;
 *                   {@code null} when no candidate reached the auto-import score
 * @param candidates ranked related candidates when {@code matched} is null (empty otherwise);
 *                   more than one means the caller should present a choice
 * @param keywords   the search keywords actually executed, for transparency and debugging
 */
public record PixivMatchResponse(
        Map<String, Object> extracted,
        PixivMatchItem matched,
        List<PixivMatchItem> candidates,
        List<String> keywords) {
}
