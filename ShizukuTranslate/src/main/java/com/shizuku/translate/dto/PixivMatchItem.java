package com.shizuku.translate.dto;

import java.util.List;

/**
 * One ranked candidate from the screenshot-match endpoint: the search row plus the ranking
 * score that placed it there (title match scores start at {@code 1000}, tag overlap adds ten
 * per shared concrete tag).
 */
public record PixivMatchItem(
        String id,
        String title,
        String author,
        List<String> tags,
        int xRestrict,
        String description,
        int textCount,
        int score) {
}
