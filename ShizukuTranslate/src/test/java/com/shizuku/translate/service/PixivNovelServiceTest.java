package com.shizuku.translate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two pure helpers behind the Pixiv import: URL -> work id and Pixiv-marked-up body ->
 * plain text. Both are easy to get subtly wrong and neither needs a live Pixiv call to test.
 */
class PixivNovelServiceTest {

    @Test
    void extractsIdFromShowPhpQuery() {
        assertEquals("123456", PixivNovelService.extractNovelId(
                "https://www.pixiv.net/novel/show.php?id=123456"));
    }

    @Test
    void extractsIdFromQueryWithOtherParameters() {
        assertEquals("123456", PixivNovelService.extractNovelId(
                "https://www.pixiv.net/novel/show.php?lang=zh&id=123456#2"));
    }

    @Test
    void extractsIdFromPathForm() {
        assertEquals("654321", PixivNovelService.extractNovelId(
                "https://www.pixiv.net/novel/654321"));
    }

    @Test
    void extractsBareId() {
        assertEquals("42", PixivNovelService.extractNovelId("42"));
    }

    @Test
    void rejectsUrlWithoutId() {
        assertNull(PixivNovelService.extractNovelId("https://www.pixiv.net/novel/show.php"));
        assertNull(PixivNovelService.extractNovelId("not a url"));
        assertNull(PixivNovelService.extractNovelId(null));
    }

    @Test
    void convertsNewpageIntoParagraphBreak() {
        assertEquals("第一页\n\n第二页",
                PixivNovelService.cleanPixivText("第一页[newpage]第二页"));
    }

    @Test
    void keepsRubyBaseTextOnly() {
        assertEquals("山咲もも",
                PixivNovelService.cleanPixivText("[[rb:山咲> やまさき]]もも"));
    }

    @Test
    void keepsInnerTextOfFormattingTags() {
        assertEquals("强调", PixivNovelService.cleanPixivText("[b:强调]"));
    }

    @Test
    void dropsControlTags() {
        assertEquals("前 后",
                PixivNovelService.cleanPixivText("前 [jump:1]后"));
        assertEquals("",
                PixivNovelService.cleanPixivText("[uploadedimage:5]"));
    }

    @Test
    void collapsesBlankLinesAndTrims() {
        assertEquals("一\n\n二",
                PixivNovelService.cleanPixivText("\n一\n\n\n\n二\n"));
    }

    @Test
    void handlesEmptyContent() {
        assertEquals("", PixivNovelService.cleanPixivText(""));
        assertEquals("", PixivNovelService.cleanPixivText(null));
    }

    @Test
    void stripsHtmlFromDescription() {
        assertEquals("novel/13246465\nまとめました", PixivNovelService.stripHtml(
                "<strong><a href=\"https://www.pixiv.net/novel/show.php?id=13246465\">novel/13246465</a></strong><br />まとめました"));
        assertEquals("a & b", PixivNovelService.stripHtml("a &amp; b"));
        assertEquals("", PixivNovelService.stripHtml(null));
    }

    @Test
    void extractsTagNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(List.of("スモロ", "ONEPIECE"), PixivNovelService.extractTags(mapper.readTree(
                "{\"tags\":[{\"tag\":\"スモロ\"},{\"tag\":\"ONEPIECE\"},{\"tag\":\"  \"}]}")));
        // Missing or malformed shape must not blow up the import.
        assertEquals(List.of(), PixivNovelService.extractTags(mapper.readTree("{}")));
    }

    @Test
    void buildsLabelledMetadataBlock() {
        assertEquals("标题：标题\n作者：作者\n标签：a、b\n简介：简介",
                PixivNovelService.buildMetadataText("标题", "作者", List.of("a", "b"), "简介"));
    }

    @Test
    void omitsMissingMetadataFields() {
        assertEquals("标题：只有标题",
                PixivNovelService.buildMetadataText("只有标题", "", List.of(), ""));
        assertEquals("", PixivNovelService.buildMetadataText("", "", List.of(), ""));
    }
}
