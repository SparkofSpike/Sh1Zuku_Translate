package com.shizuku.translate.controller;

import com.shizuku.translate.dto.PixivNovelResponse;
import com.shizuku.translate.service.PixivNovelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Imports Pixiv novel text for the web translate box. Kept unauthenticated like the other
 * read-only reference endpoints ({@code /presets}, {@code /translation/languages}) so the
 * import box also works on the pre-login landing page; it only proxies a public Pixiv
 * endpoint and stores nothing.
 */
@RestController
@RequestMapping("/api/v1")
public class PixivController {

    private final PixivNovelService pixivNovelService;

    public PixivController(PixivNovelService pixivNovelService) {
        this.pixivNovelService = pixivNovelService;
    }

    @GetMapping("/pixiv/novel")
    public PixivNovelResponse importNovel(@RequestParam("url") String url) {
        return pixivNovelService.importNovel(url);
    }
}
