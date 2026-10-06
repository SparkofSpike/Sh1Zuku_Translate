package com.shizuku.translate.service.longform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the terminology parsing/rendering helpers (the parts that do not need a model).
 */
class TerminologyServiceTest {

    // ----- parseTermPairs -----

    @Test
    void parseTermPairsValidJson() {
        List<TerminologyService.TermPair> terms = TerminologyService.parseTermPairs(
                "[{\"term\":\"Alice\",\"translation\":\"愛丽丝\",\"type\":\"person\"},"
                        + "{\"term\":\"魔法阵\",\"translation\":\"魔法阵\",\"type\":\"concept\"}]");
        assertEquals(2, terms.size());
        assertEquals("Alice", terms.get(0).source());
        assertEquals("愛丽丝", terms.get(0).translation());
        assertEquals("魔法阵", terms.get(1).source());
        assertEquals("魔法阵", terms.get(1).translation());
    }

    @Test
    void parseTermPairsToleratesProseAroundArray() {
        List<TerminologyService.TermPair> terms = TerminologyService.parseTermPairs(
                "[{\"term\":\"Alice\",\"translation\":\"愛丽丝\"}] follow-up text here");
        assertEquals(1, terms.size());
        assertEquals("Alice", terms.get(0).source());
    }

    @Test
    void parseTermPairsGarbageYieldsNone() {
        assertTrue(TerminologyService.parseTermPairs("not json at all").isEmpty());
    }

    @Test
    void parseTermPairsEmptyArrayYieldsNone() {
        assertTrue(TerminologyService.parseTermPairs("[]").isEmpty());
    }

    @Test
    void parseTermPairsSkipsEmptyFieldsAndTrims() {
        List<TerminologyService.TermPair> terms = TerminologyService.parseTermPairs(
                "[{\"term\":\"\",\"translation\":\"x\"},"
                        + "{\"term\":\"A\",\"translation\":\"  译A  \"},"
                        + "{\"term\":\"B\",\"translation\":\"\"}]");
        // Only the entry with a both a term and a translation survives; the translation is trimmed.
        assertEquals(1, terms.size());
        assertEquals("A", terms.get(0).source());
        assertEquals("译A", terms.get(0).translation());
    }

    // ----- parseAuditFindings -----

    @Test
    void parseAuditFindingsValidJson() {
        List<String[]> pairs = TerminologyService.parseAuditFindings(
                "[{\"standard\":\"魔法阵\",\"variants\":[\"魔导阵\",\"魔法军\"]},"
                        + "{\"standard\":\"龙\",\"variants\":[\"龙萧\"]}]");
        assertEquals(3, pairs.size());
        assertEquals("魔法阵", pairs.get(0)[0]);
        assertEquals("魔导阵", pairs.get(0)[1]);
        assertEquals("魔法阵", pairs.get(1)[0]);
        assertEquals("魔法军", pairs.get(1)[1]);
        assertEquals("龙", pairs.get(2)[0]);
        assertEquals("龙萧", pairs.get(2)[1]);
    }

    @Test
    void parseAuditFindingsToleratesProse() {
        List<String[]> pairs = TerminologyService.parseAuditFindings(
                "结果如下：[{\"standard\":\"AA\",\"variants\":[\"bb\"]}]，校对完毕");
        assertEquals(1, pairs.size());
        assertEquals("AA", pairs.get(0)[0]);
        assertEquals("bb", pairs.get(0)[1]);
    }

    @Test
    void parseAuditFindingsGarbageYieldsNone() {
        assertTrue(TerminologyService.parseAuditFindings("garbage {{{").isEmpty());
    }

    @Test
    void parseAuditFindingsEmptyArrayYieldsNone() {
        assertTrue(TerminologyService.parseAuditFindings("[]").isEmpty());
    }

    @Test
    void parseAuditFindingsSkipsVariantsShorterThanTwoChars() {
        String rule = "[{\"standard\":\"A\",\"variants\":[\"X\",\"YY\",\"Z\"]}]";
        List<String[]> pairs = TerminologyService.parseAuditFindings(rule);
        // "X" and "Z" are length 1 and are skipped; only the two-char "YY" survives.
        assertEquals(1, pairs.size());
        assertEquals("YY", pairs.get(0)[1]);
    }

    @Test
    void parseAuditFindingsSkipsVariantEqualToStandard() {
        // A no-op replacement ("AB" -> "AB") must be dropped; the distinct "CD" survives.
        List<String[]> pairs = TerminologyService.parseAuditFindings(
                "[{\"standard\":\"AB\",\"variants\":[\"AB\",\"CD\"]}]");
        assertEquals(1, pairs.size());
        assertEquals("CD", pairs.get(0)[1]);
    }

    // ----- renderTermBlock -----

    @Test
    void renderTermBlockEmptyListProducesJustHeaderAndPunctuation() {
        String block = TerminologyService.renderTermBlock(List.of());
        // Header with its trailing colon plus the closing period and no stray separator.
        assertEquals("【本文专有名词对照表】（自动提取，翻译时必须严格遵循这些译名）：。", block);
    }

    @Test
    void renderTermBlockRemovesTrailingSeparator() {
        List<TerminologyService.TermPair> terms = List.of(
                new TerminologyService.TermPair("Alice", "愛丽丝"),
                new TerminologyService.TermPair("Magic", "魔法"));
        String block = TerminologyService.renderTermBlock(terms);
        assertEquals("【本文专有名词对照表】（自动提取，翻译时必须严格遵循这些译名）：Alice→愛丽丝、Magic→魔法。", block);
    }
}