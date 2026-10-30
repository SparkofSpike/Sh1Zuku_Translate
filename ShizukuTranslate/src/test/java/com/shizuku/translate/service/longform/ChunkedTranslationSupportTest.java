package com.shizuku.translate.service.longform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the chunked long-novel helpers: paragraph-aligned splitting and the
 * continuity-anchor user message.
 */
class ChunkedTranslationSupportTest {

    @Test
    void singleChunkWhenTextFitsWithinUnit() {
        String text = "abc\ndef\nghi";
        List<String> chunks = ChunkedTranslationSupport.splitIntoChunks(text, 100);
        assertEquals(List.of(text), chunks);
    }

    @Test
    void exactChunkCountForRequestedUnit() {
        // Five lines of "line\n" (5 chars each) => 25 chars total. With a unit of 12 the
        // algorithm targets ceil(25/3)=9 chars per chunk but pulls every boundary forward
        // to a newline, so it always yields exactly ceil(25/12)=3 chunks.
        String text = "line\n".repeat(5);
        List<String> chunks = ChunkedTranslationSupport.splitIntoChunks(text, 12);
        assertEquals(3, chunks.size());
    }

    @Test
    void boundariesPulledToNewlinesNeverSplitInsideLine() {
        // Three lines of "0123456789\\n" (11 chars each, 33 total). unit=15 targets
        // ceil(33/3)=11 chars per chunk, which already lands on a line end, so the split is
        // accepted as-is: three whole lines, no chunk cut mid-line.
        String line = "0123456789\n".repeat(3);
        List<String> chunks = ChunkedTranslationSupport.splitIntoChunks(line, 15);
        assertEquals(3, chunks.size());
        // Every chunk but the last must end exactly where a line ends (right after '\n').
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertTrue(chunks.get(i).endsWith("\n"), "chunk " + i + " must end at a line boundary");
            // No chunk may start in the middle of a line: the preceding text must have ended
            // with the prior chunk, so reassembling must reproduce the original exactly.
        }
        assertEquals(line, String.join("", chunks));
    }

    @Test
    void longLineWithoutNewlinesIsCutAtTheTarget() {
        // A novel with no blank lines, or one giant line: following the newline blindly used to
        // produce a 46k-char chunk that no output cap can answer. The pull-forward is bounded by
        // half a chunk, so the cut is forced at the target instead.
        String text = "x".repeat(100_000);
        List<String> chunks = ChunkedTranslationSupport.splitIntoChunks(text, 42_000);
        assertEquals(text, String.join("", chunks));
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 42_000 + 21_000,
                    "a chunk without a nearby newline must not blow past the unit");
        }
    }

    @Test
    void fullCoverageNoEmptyChunksNeverSplitsLine() {
        // Mixed length lines; the unit is larger than every line, so each boundary still lands
        // on a line end and the split stays clean.
        String[] lines = {"alpha beta gamma delta", "one", "two three four five six seven",
                "short", "a bit longer line here", "zeta"};
        String text = String.join("\n", lines) + "\n";
        List<String> chunks = ChunkedTranslationSupport.splitIntoChunks(text, 30);
        assertFalse(chunks.isEmpty());
        // Full coverage and no empty chunks.
        assertEquals(text, String.join("", chunks));
        for (String chunk : chunks) {
            assertFalse(chunk.isEmpty());
        }
        // Every chunk boundary respects lines.
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertTrue(chunks.get(i).endsWith("\n"));
        }
    }

    @Test
    void firstChunkHasNoAnchorSection() {
        List<String> chunks = List.of("only-chunk");
        List<String> translated = List.of("t");
        String msg = ChunkedTranslationSupport.buildChunkUserMessage(chunks, translated, 0);
        assertEquals("only-chunk", msg);
    }

    @Test
    void laterChunkCarriesPreviousSourceAndTranslationTails() {
        List<String> chunks = List.of("先頭の原文", "次ぎの段落");
        List<String> translated = List.of("頭の訳文", "次の訳文");
        String msg = ChunkedTranslationSupport.buildChunkUserMessage(chunks, translated, 1);
        // The anchor section carries the previous source and translation tails verbatim.
        assertTrue(msg.contains("原文：…先頭の原文"), "should carry previous source tail");
        assertTrue(msg.contains("译文：…頭の訳文"), "should carry previous translation tail");
        // The current chunk is the segment to translate, not the anchor text.
        assertTrue(msg.contains("次ぎの段落"), "should ask to continue with the current chunk");
        assertTrue(msg.contains("前文末尾，仅供衔接参考，请勿重复翻译"), "should keep the continuity-anchor marker");
    }

    @Test
    void tailsTruncatedToFixedWindow() {
        // Previous source is far longer than the tail window; only the last chars survive.
        String longSource = "0123456789".repeat(100); // 1000 chars
        String longTranslation = "訳" + "Z".repeat(900);
        List<String> chunks = List.of(longSource, "current");
        List<String> translated = List.of(longTranslation, "later");
        String msg = ChunkedTranslationSupport.buildChunkUserMessage(chunks, translated, 1);
        // Tail is limited to 400 chars, so the long analysis must not leak its head.
        int sourceIdx = msg.indexOf("…");
        assertTrue(msg.indexOf(longSource) < 0, "whole source must not be embedded");
        assertTrue(msg.contains(longSource.substring(longSource.length() - 400)));
        assertTrue(msg.contains(longTranslation.substring(longTranslation.length() - 400)));
    }
}