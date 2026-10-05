package com.shizuku.translate.controller;

import com.shizuku.translate.dto.PixivNovelResponse;
import com.shizuku.translate.dto.PixivSearchItem;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.service.PixivImageImportService;
import com.shizuku.translate.service.PixivNovelService;
import com.shizuku.translate.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pixiv import endpoints for the web import panel: fetch a novel by URL/id, search novels by
 * keyword, and extract novel metadata from a pasted screenshot.
 *
 * <p>Fetching and searching are unauthenticated like the other read-only reference endpoints
 * ({@code /presets}, {@code /translation/languages}) so the import box also works on the
 * pre-login landing page; they only proxy public Pixiv endpoints and store nothing. The
 * screenshot endpoint is different — it runs the site's paid vision model, so it requires an
 * authenticated, email-verified account.
 */
@RestController
@RequestMapping("/api/v1")
public class PixivController {

    private static final int MAX_EXTRACT_IMAGES = 3;

    private final PixivNovelService pixivNovelService;
    private final PixivImageImportService pixivImageImportService;
    private final UserService userService;

    public PixivController(PixivNovelService pixivNovelService,
                           PixivImageImportService pixivImageImportService,
                           UserService userService) {
        this.pixivNovelService = pixivNovelService;
        this.pixivImageImportService = pixivImageImportService;
        this.userService = userService;
    }

    @GetMapping("/pixiv/novel")
    public PixivNovelResponse importNovel(@RequestParam("url") String url) {
        return pixivNovelService.importNovel(url);
    }

    @GetMapping("/pixiv/search")
    public List<PixivSearchItem> searchNovels(@RequestParam("keyword") String keyword) {
        return pixivNovelService.searchNovels(keyword);
    }

    /** Screenshot → vision model → {title, author, tags[], summary}. */
    @PostMapping("/pixiv/extract")
    public Map<String, Object> extractNovelInfo(
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            Principal principal) throws IOException {
        userService.requireEmailVerified(principal.getName());
        if (images == null || images.isEmpty()) {
            throw new BusinessException("请上传至少一张图片");
        }
        if (images.size() > MAX_EXTRACT_IMAGES) {
            throw new BusinessException("一次最多识别 " + MAX_EXTRACT_IMAGES + " 张图片");
        }
        List<byte[]> payloads = new ArrayList<>();
        List<String> mediaTypes = new ArrayList<>();
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty()) {
                continue;
            }
            payloads.add(image.getBytes());
            mediaTypes.add(image.getContentType());
        }
        if (payloads.isEmpty()) {
            throw new BusinessException("请上传至少一张图片");
        }
        return pixivImageImportService.extractNovelInfo(payloads, mediaTypes);
    }
}
