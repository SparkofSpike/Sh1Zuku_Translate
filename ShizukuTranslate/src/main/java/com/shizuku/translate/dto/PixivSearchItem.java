package com.shizuku.translate.dto;

import java.util.List;

/**
 * One novel row from the Pixiv search endpoint, as returned to the screenshot-import flow's
 * candidate list. {@code xRestrict} is Pixiv's content rating (0 = all-ages, 1 = R-18,
 * 2 = R-18G) so the client can label restricted works.
 */
public record PixivSearchItem(
        String id,
        String title,
        String author,
        List<String> tags,
        int xRestrict,
        String description,
        int textCount) {
}
