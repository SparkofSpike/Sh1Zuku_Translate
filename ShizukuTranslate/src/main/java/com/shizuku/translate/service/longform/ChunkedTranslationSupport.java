package com.shizuku.translate.service.longform;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure helpers for the chunked long-novel pipeline: paragraph-aligned chunk splitting and the
 * continuity-anchor user message built from the previous chunk's source and translation tails.
 *
 * <p>All methods are static and free of Spring dependencies so the splitting and message-building
 * rules can be unit-tested in isolation.
 */
public final class ChunkedTranslationSupport {

    private ChunkedTranslationSupport() {
    }

    /**
     * Splits {@code text} into paragraph-aligned chunks of roughly {@code chunkUnit} characters,
     * never breaking inside a line: every boundary is pulled forward to the next newline, so a
     * chunk always ends where a line (usually a paragraph) ends.
     */
    public static List<String> splitIntoChunks(String text, int chunkUnit) {
        int total = text.length();
        int count = Math.max(1, (int) Math.ceil(total / (double) chunkUnit));
        int target = (int) Math.ceil(total / (double) count);
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < total) {
            int remaining = count - out.size();
            if (remaining <= 1) {
                out.add(text.substring(start));
                break;
            }
            int targetEnd = Math.min(total, start + target);
            int end = targetEnd;
            if (targetEnd < total) {
                int newline = text.indexOf('\n', targetEnd);
                end = (newline >= 0) ? newline + 1 : total;
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    /**
     * User message for one chunk: the previous chunk's source and translation tails serve as the
     * conversation history (what happened, how it was phrased), then the text to translate.
     */
    public static String buildChunkUserMessage(List<String> chunks, List<String> translatedSoFar, int index) {
        String current = chunks.get(index);
        if (index == 0) {
            return current;
        }
        String prevSource = tail(chunks.get(index - 1), 400);
        String prevTranslation = tail(translatedSoFar.get(index - 1), 400);
        return "【前文末尾，仅供衔接参考，请勿重复翻译】\n原文：…" + prevSource
                + "\n译文：…" + prevTranslation
                + "\n\n【请从下方内容开始继续翻译，保持人称、术语、语气与前文完全一致】\n" + current;
    }

    static String tail(String value, int max) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= max ? value : value.substring(value.length() - max);
    }
}