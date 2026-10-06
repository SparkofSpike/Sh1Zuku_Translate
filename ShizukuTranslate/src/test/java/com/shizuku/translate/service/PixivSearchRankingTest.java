package com.shizuku.translate.service;

import com.shizuku.translate.dto.PixivSearchItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ranking behaviour behind "paste a screenshot → the original work is found" — the same cases
 * the web client's {@code pixiv-search-rank.test.ts} asserts, so the two implementations
 * cannot drift apart. Fixture data is taken from a real production run.
 */
class PixivSearchRankingTest {

    /** Mirrors the recognised metadata of the production regression case. */
    private static final String TITLE = "私の幸せな日々";
    private static final List<String> TAGS = List.of("BLACKSOULS", "BLACKSOULS2", "紅ずきん", "小红帽");

    private static PixivSearchItem item(String id, String title, String... tags) {
        return new PixivSearchItem(id, title, "", List.of(tags), 0, "", 0);
    }

    private static int scoreAgainstRecognised(PixivSearchItem candidate) {
        return PixivSearchRanking.scoreCandidate(TITLE, TAGS, candidate, false);
    }

    @Test
    void ranksTheExactWorkAboveEveryOtherCandidate() {
        PixivSearchItem target = item("28032455", TITLE, "BLACKSOULS", "BLACKSOULS2", "紅ずきん");
        PixivSearchItem lookalike = item("28521517", "私の幸せな日常", "ようこそ実力至上主義の教室へ");
        PixivSearchItem tagOnly = item("29211377", "旅の劇団と黒の不死者", "BLACKSOULS", "Fate/GrandOrder");

        int targetScore = scoreAgainstRecognised(target);
        int lookalikeScore = scoreAgainstRecognised(lookalike);
        int tagOnlyScore = scoreAgainstRecognised(tagOnly);

        assertTrue(targetScore >= PixivSearchRanking.AUTO_IMPORT_SCORE);
        // The near-miss title must never reach auto-import level.
        assertTrue(lookalikeScore < PixivSearchRanking.AUTO_IMPORT_SCORE);
        assertTrue(targetScore > tagOnlyScore);
        assertTrue(tagOnlyScore > lookalikeScore);
    }

    @Test
    void titleMatchOutranksAnyAmountOfTagOverlap() {
        int titleMatch = scoreAgainstRecognised(item("a", TITLE));
        int tagHeavy = scoreAgainstRecognised(item("b", "全然違う話", "BLACKSOULS", "BLACKSOULS2", "紅ずきん", "小红帽"));
        assertTrue(titleMatch > tagHeavy);
    }

    @Test
    void belowTheTitleLevelMoreTagOverlapWins() {
        int twoTags = scoreAgainstRecognised(item("a", "別の小説", "BLACKSOULS", "BLACKSOULS2"));
        int oneTag = scoreAgainstRecognised(item("b", "別の小説", "BLACKSOULS"));
        assertTrue(twoTags > oneTag);
    }

    @Test
    void matchesTitlesTolerantly() {
        assertTrue(PixivSearchRanking.titleMatches("私の幸せな日々", "【改稿】私の幸せな日々（完結）"));
        assertTrue(PixivSearchRanking.titleMatches("DEATH NOTE", "Death Note"));
        // Too short to be meaningful: a 1-character title would match half the site.
        assertFalse(PixivSearchRanking.titleMatches("短", "短編集"));
    }

    @Test
    void treatsTwoCjkCharactersAsADistinctiveTitle() {
        assertTrue(PixivSearchRanking.titleMatches("熱平衡", "熱平衡"));
        assertTrue(PixivSearchRanking.titleMatches("熱平衡", "ある夜の熱平衡"));
        assertTrue(PixivSearchRanking.isUsableTitle("熱平衡", List.of("超かぐや姫!", "酒寄彩葉")));
        // Latin stays strict.
        assertFalse(PixivSearchRanking.titleMatches("R-18", "R-18"));
    }

    @Test
    void prefersExactTitleOverContainmentMatch() {
        List<String> infoTags = List.of("超かぐや姫!", "酒寄彩葉", "月見ヤチヨ", "現パロ", "曲パロ");
        int exact = PixivSearchRanking.scoreCandidate("熱平衡", infoTags,
                item("exact", "熱平衡", "超かぐや姫!", "酒寄彩葉", "月見ヤチヨ", "現パロ"), false);
        int sameTitleOtherWork = PixivSearchRanking.scoreCandidate("熱平衡", infoTags,
                item("decoy", "熱平衡 ", "つりライフ", "つりぷら"), false);
        int contained = PixivSearchRanking.scoreCandidate("熱平衡", infoTags,
                item("contained", "ある夜の熱平衡", "腐向け"), false);

        assertTrue(exact >= PixivSearchRanking.AUTO_IMPORT_SCORE);
        assertTrue(exact > sameTitleOtherWork);
        assertTrue(sameTitleOtherWork > contained);
        assertEquals(PixivSearchRanking.TITLE_EXACT_SCORE, sameTitleOtherWork);
    }

    @Test
    void extractsTheLongestCleanTitleRun() {
        assertEquals("未知的命运",
                PixivSearchRanking.titleSearchChunk("第四篇（中） 未知的命运，未尽的余韵，未卜的前路--X.命运之轮（逆位）"));
        assertEquals("私の幸せな日々", PixivSearchRanking.titleSearchChunk("私の幸せな日々"));
    }

    @Test
    void buildsTitleSearchKeywordsFullTitleThenLongestRun() {
        assertEquals(List.of("紅ずきんとグリムの秘話　ラドヴィッジ市街上層にて", "ラドヴィッジ市街上層にて"),
                PixivSearchRanking.titleSearchKeywords("紅ずきんとグリムの秘話　ラドヴィッジ市街上層にて"));
        assertEquals(List.of("私の幸せな日々"), PixivSearchRanking.titleSearchKeywords("私の幸せな日々"));
        assertEquals(List.of(), PixivSearchRanking.titleSearchKeywords(""));
    }

    @Test
    void normalisesRomanAndFullWidthDigits() {
        assertEquals("BLACKSOULS2", PixivSearchRanking.normalizeTagForSearch("BLACKSOULSⅡ"));
        assertEquals("VOL.3", PixivSearchRanking.normalizeTagForSearch("VOL.Ⅲ"));
        assertEquals("123", PixivSearchRanking.normalizeTagForSearch("１２３"));
    }

    @Test
    void buildsBothKeywordVariantsWhenDigitsDiffer() {
        assertEquals(List.of("BLACKSOULS2", "BLACKSOULSⅡ"),
                PixivSearchRanking.tagSearchKeywords(List.of("BLACKSOULSⅡ")));
        assertEquals(List.of("私の幸せな日々"),
                PixivSearchRanking.tagSearchKeywords(List.of("私の幸せな日々")));
        assertEquals(List.of(), PixivSearchRanking.tagSearchKeywords(List.of()));
    }

    @Test
    void flagsWhichKeywordsAreTrustworthy() {
        assertTrue(PixivSearchRanking.isTrustedKeyword("BLACKSOULS2"));
        assertTrue(PixivSearchRanking.isTrustedKeyword("私の幸せな日々"));
        assertFalse(PixivSearchRanking.isTrustedKeyword("BLACKSOULSⅡ"));
        assertFalse(PixivSearchRanking.isTrustedKeyword("VOL.Ⅲ"));
    }

    @Test
    void trustedQueryHitOutranksWorkMerelyTaggedWithRomanForm() {
        List<String> infoTags = List.of("R-18", "BLACKSOULSⅡ");
        int scoreRoman = PixivSearchRanking.scoreCandidate("", infoTags,
                item("28610144", "第 二 乃幕 【確認】", "BLACKSOULSⅡ"), false);
        int scoreTarget = PixivSearchRanking.scoreCandidate("", infoTags,
                item("28032455", TITLE, "BLACKSOULS2"), true);
        assertTrue(scoreTarget > scoreRoman);
        assertEquals(PixivSearchRanking.PRECISE_VARIANT_BONUS, scoreTarget - scoreRoman);
    }

    @Test
    void filtersGenericTagsFromSearchPlanning() {
        List<String> concrete = PixivSearchRanking.concreteTags(List.of("R-18", "BLACKSOULSⅡ"));
        assertEquals(List.of("BLACKSOULSⅡ"), concrete);
        assertEquals(List.of("BLACKSOULS2", "BLACKSOULSⅡ"), PixivSearchRanking.tagSearchKeywords(concrete));
        // All-generic input falls back to the original list rather than searching nothing.
        assertEquals(List.of("R-18", "オリジナル"), PixivSearchRanking.concreteTags(List.of("R-18", "オリジナル")));
    }

    @Test
    void rejectsATagPromotedToTheTitleField() {
        assertFalse(PixivSearchRanking.isUsableTitle("R-18", List.of("R-18", "BLACKSOULSⅡ")));
        assertFalse(PixivSearchRanking.isUsableTitle("BLACKSOULSⅡ", List.of("BLACKSOULSⅡ")));
        assertTrue(PixivSearchRanking.isUsableTitle(TITLE, List.of("BLACKSOULS2")));
        assertFalse(PixivSearchRanking.isUsableTitle("", List.of()));
    }

    @Test
    void scoresDigitNormalisedTagOverlapAndIgnoresGenericTags() {
        List<String> infoTags = List.of("R-18", "BLACKSOULSⅡ");
        int target = PixivSearchRanking.scoreCandidate("", infoTags,
                item("t", "元作品", "BLACKSOULS", "BLACKSOULS2"), false);
        int unrelated = PixivSearchRanking.scoreCandidate("", infoTags,
                item("u", "别的作品", "R-18", "Gore"), false);
        assertTrue(target > unrelated);
        // The unrelated work carries R-18 too, which is generic and must not score.
        assertEquals(0, unrelated);
    }
}
