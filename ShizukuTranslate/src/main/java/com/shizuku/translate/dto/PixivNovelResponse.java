package com.shizuku.translate.dto;

import java.util.List;

/**
 * A Pixiv novel imported from its work page, ready to be pasted into the translate textarea.
 *
 * @param novelId      the numeric Pixiv work id the URL pointed at
 * @param title        the novel title
 * @param author       the author name, empty when Pixiv did not report one
 * @param description  the work summary as plain text (Pixiv's HTML stripped), possibly empty
 * @param tags         the work tags in Pixiv's order, possibly empty
 * @param text         the novel body with Pixiv's control tags removed, newlines kept
 * @param metadataText title / author / tags / description assembled into a labelled block,
 *                     so a client can send it to the translate endpoint as one request
 */
public record PixivNovelResponse(String novelId,
                                 String title,
                                 String author,
                                 String description,
                                 List<String> tags,
                                 String text,
                                 String metadataText) {
}
