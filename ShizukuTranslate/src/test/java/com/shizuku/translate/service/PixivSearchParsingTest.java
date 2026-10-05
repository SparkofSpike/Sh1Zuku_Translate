package com.shizuku.translate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.PixivSearchItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Search-response parsing behind the screenshot-import flow: the candidate list is built from
 * Pixiv's {@code body.novel.data[]} shape, and malformed input must degrade to an empty list
 * rather than failing the request.
 */
class PixivSearchParsingTest {

    private PixivNovelService service() {
        return new PixivNovelService(new ObjectMapper(), new AppConfig.AppProperties());
    }

    @Test
    void parsesSearchResultsWithRatingAndTags() {
        String raw = """
                {"error":false,"body":{"novel":{"data":[
                  {"id":"29309804","title":"作品一","xRestrict":0,"tags":["R-18","ctq"],
                   "userName":"みかん","description":"24<br />※数字稼働","textCount":2476},
                  {"id":"11112222","title":"作品二","xRestrict":1,"tags":["オリジナル"],
                   "userName":"作者B","description":"","textCount":999}
                ],"total":2}}}
                """;
        List<PixivSearchItem> items = service().parseSearchResults(raw);

        assertEquals(2, items.size());
        PixivSearchItem first = items.get(0);
        assertEquals("29309804", first.id());
        assertEquals("作品一", first.title());
        assertEquals("みかん", first.author());
        assertEquals(0, first.xRestrict());
        assertEquals(List.of("R-18", "ctq"), first.tags());
        assertEquals(2476, first.textCount());
        assertTrue(first.description().contains("※数字稼働"), "description HTML must be stripped");
        assertEquals(1, items.get(1).xRestrict());
    }

    @Test
    void malformedSearchResponseYieldsEmptyList() {
        assertEquals(0, service().parseSearchResults(null).size());
        assertEquals(0, service().parseSearchResults("").size());
        assertEquals(0, service().parseSearchResults("not json").size());
        assertEquals(0, service().parseSearchResults("{\"error\":false,\"body\":{}}").size());
        // Rows without an id or title are dropped instead of producing broken candidates.
        assertEquals(0, service().parseSearchResults(
                "{\"body\":{\"novel\":{\"data\":[{\"title\":\"no id\"},{\"id\":\"1\"}]}}}").size());
    }

    @Test
    void emptyDataArrayYieldsEmptyList() {
        assertEquals(0, service().parseSearchResults("{\"body\":{\"novel\":{\"data\":[]}}}").size());
    }

    @Test
    void mapsPublicModeNamesToPixivSModes() {
        // Pixiv's novel search has one useful mode: s_tag matches tags, title and description
        // with partial matching (the mode pixiv's own search box sends). The old values were
        // wrong: s_tag_full is exact-tag matching and s_tc searches the body text — long title
        // keywords return 0 results on both (verified against the live API on 2026-10-06:
        // a 12-character title fragment finds the work on s_tag only).
        assertEquals("s_tag", PixivNovelService.sModeValue(null));
        assertEquals("s_tag", PixivNovelService.sModeValue("tag"));
        assertEquals("s_tag", PixivNovelService.sModeValue("anything"));
        assertEquals("s_tag", PixivNovelService.sModeValue("title"));
        assertEquals("s_tag", PixivNovelService.sModeValue("TITLE"));
    }
}
