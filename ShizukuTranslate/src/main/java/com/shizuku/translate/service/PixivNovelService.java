package com.shizuku.translate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.PixivNovelResponse;
import com.shizuku.translate.dto.PixivSearchItem;
import com.shizuku.translate.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports a Pixiv novel's text from its work URL so the web UI can paste it into the
 * translate box without the user opening Pixiv first, and searches Pixiv for novels by
 * keyword (used by the screenshot-import flow).
 *
 * <p>The text is pulled from Pixiv's own AJAX endpoint
 * ({@code https://www.pixiv.net/ajax/novel/{id}}) and returned with Pixiv's control tags
 * stripped. Public novels work without a session; login-gated (for example R-18) novels
 * need the server-side {@code PHPSESSID} configured under {@code app.pixiv.session-cookie}
 * — the same pattern large archives use (one site-level account cookie). Without it those
 * works answer 403 and are reported as such instead of silently importing an empty text.
 */
@Service
public class PixivNovelService {

    private static final Logger log = LoggerFactory.getLogger(PixivNovelService.class);

    /** {@code https://www.pixiv.net/novel/show.php?id=123} and {@code ...?foo=1&id=123}. */
    private static final Pattern ID_FROM_QUERY = Pattern.compile("[?&]id=(\\d+)");
    /** {@code https://www.pixiv.net/novel/123} (the path-style work URL). */
    private static final Pattern ID_FROM_PATH = Pattern.compile("/novel/(\\d+)");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AppConfig.AppProperties appProperties;

    public PixivNovelService(ObjectMapper objectMapper, AppConfig.AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(15_000);
        requestFactory.setReadTimeout(30_000);
        this.restClient = RestClient.builder()
                .baseUrl("https://www.pixiv.net")
                .requestFactory(requestFactory)
                // Pixiv serves this endpoint to ordinary browser requests; a bare client
                // without a UA is treated differently, so send a plain one.
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .build();
    }

    /**
     * Fetches one novel by URL or bare id.
     *
     * @throws BusinessException when the URL carries no id, the novel is missing or
     *                           login-gated, or Pixiv is unreachable
     */
    public PixivNovelResponse importNovel(String url) {
        String novelId = extractNovelId(url);
        if (novelId == null) {
            throw new BusinessException("无法从链接中识别 Pixiv 小说 ID，请粘贴小说页面链接");
        }

        String raw;
        try {
            raw = restClient.get()
                    .uri("/ajax/novel/{id}", novelId)
                    .header(HttpHeaders.REFERER, "https://www.pixiv.net/novel/show.php?id=" + novelId)
                    .headers(this::applySessionCookie)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("Pixiv novel {} import failed with HTTP {} (session cookie {}present)",
                    novelId, status, hasSessionCookie() ? "" : "not ");
            if (status == 404) {
                // Without a session Pixiv hides R-18 works as 404, so both causes are named.
                throw new BusinessException(hasSessionCookie()
                        ? "Pixiv 上找不到这篇小说（也可能被账号的閲覧設定屏蔽），请确认链接是否正确"
                        : "Pixiv 上找不到这篇小说；如果它是 R-18 作品，需要服务器配置 Pixiv 会话才能导入，请先登录 Pixiv 手动复制原文");
            }
            if (status == 403 || status == 401) {
                throw new BusinessException(hasSessionCookie()
                        ? "Pixiv 拒绝了请求：服务器会话可能已失效，或该账号未开启 R-18 显示设置"
                        : "该小说需要登录 Pixiv 才能查看，请先登录 Pixiv 后手动复制原文");
            }
            throw new BusinessException("Pixiv 请求失败（HTTP " + status + "）");
        } catch (RestClientException e) {
            log.warn("Pixiv novel {} import failed: {}", novelId, e.getMessage());
            throw new BusinessException("无法连接 Pixiv，请稍后重试", e);
        }

        if (raw == null || raw.isBlank()) {
            throw new BusinessException("Pixiv 返回了空内容，请稍后重试");
        }

        JsonNode body;
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.path("error").asBoolean(false) || root.path("body").isMissingNode()) {
                throw new BusinessException("Pixiv 返回错误，小说可能不存在或需要登录");
            }
            body = root.path("body");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Pixiv 返回了无法解析的内容", e);
        }

        String text = cleanPixivText(body.path("content").asText(""));
        if (text.isBlank()) {
            throw new BusinessException("这篇小说没有可导入的正文");
        }
        String title = body.path("title").asText("");
        String author = body.path("userName").asText("");
        String description = stripHtml(body.path("description").asText(""));
        List<String> tags = extractTags(body.path("tags"));
        return new PixivNovelResponse(
                body.path("id").asText(novelId),
                title,
                author,
                description,
                tags,
                text,
                buildMetadataText(title, author, tags, description));
    }

    /**
     * Searches Pixiv novels by keyword (the screenshot-import flow searches with the title and
     * tags the vision model extracted). Returns the first page of results; an empty list when
     * Pixiv matched nothing. R-18 results only appear when a session cookie is configured.
     */
    public List<PixivSearchItem> searchNovels(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new BusinessException("请输入搜索关键词");
        }
        String word = keyword.trim();
        String raw;
        try {
            raw = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/ajax/search/novels/{word}")
                            .queryParam("word", word)
                            .queryParam("mode", "all")
                            .queryParam("p", 1)
                            .build(word))
                    .header(HttpHeaders.REFERER, "https://www.pixiv.net/")
                    .headers(this::applySessionCookie)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            log.warn("Pixiv search for '{}' failed with HTTP {}", word, e.getStatusCode().value());
            throw new BusinessException("Pixiv 搜索失败（HTTP " + e.getStatusCode().value() + "）");
        } catch (RestClientException e) {
            log.warn("Pixiv search for '{}' failed: {}", word, e.getMessage());
            throw new BusinessException("无法连接 Pixiv，请稍后重试", e);
        }
        return parseSearchResults(raw);
    }

    /** Parses {@code body.novel.data[]} out of the search response; malformed input yields an empty list. */
    List<PixivSearchItem> parseSearchResults(String raw) {
        List<PixivSearchItem> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.path("body").path("novel").path("data");
            if (!data.isArray()) {
                return items;
            }
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                String title = node.path("title").asText("");
                if (id.isBlank() || title.isBlank()) {
                    continue;
                }
                items.add(new PixivSearchItem(
                        id,
                        title,
                        node.path("userName").asText(""),
                        extractPlainTagList(node.path("tags")),
                        node.path("xRestrict").asInt(0),
                        stripHtml(node.path("description").asText("")),
                        node.path("textCount").asInt(0)));
            }
        } catch (Exception e) {
            log.warn("无法解析 Pixiv 搜索响应", e);
        }
        return items;
    }

    /** Search results carry tags as a bare string array (not the {@code tags.tags[].tag} shape). */
    static List<String> extractPlainTagList(JsonNode tagsNode) {
        List<String> tags = new ArrayList<>();
        if (!tagsNode.isArray()) {
            return tags;
        }
        for (JsonNode tag : tagsNode) {
            String value = tag.asText("").trim();
            if (!value.isEmpty()) {
                tags.add(value);
            }
        }
        return tags;
    }

    private boolean hasSessionCookie() {
        String cookie = appProperties.getPixivSessionCookie();
        return cookie != null && !cookie.isBlank();
    }

    /** Sends the configured site-level session; never logs or echoes the value. */
    private void applySessionCookie(HttpHeaders headers) {
        String cookie = appProperties.getPixivSessionCookie();
        if (cookie != null && !cookie.isBlank()) {
            headers.set(HttpHeaders.COOKIE, "PHPSESSID=" + cookie.trim());
        }
    }

    /** Pixiv tags live under {@code body.tags.tags[].tag}; missing or malformed shapes yield an empty list. */
    static List<String> extractTags(JsonNode tagsNode) {
        List<String> tags = new ArrayList<>();
        JsonNode list = tagsNode.path("tags");
        if (!list.isArray()) {
            return tags;
        }
        for (JsonNode tag : list) {
            String value = tag.path("tag").asText("").trim();
            if (!value.isEmpty()) {
                tags.add(value);
            }
        }
        return tags;
    }

    /**
     * Assembles the metadata into the labelled block the translate box receives. The labels are
     * what make the block understandable to the model on its own, so a client can send it as a
     * normal translate request without any extra prompt wiring. Returns an empty string when
     * Pixiv reported none of the fields, so an empty block is never pasted.
     */
    static String buildMetadataText(String title, String author, List<String> tags, String description) {
        StringBuilder builder = new StringBuilder();
        if (title != null && !title.isBlank()) {
            builder.append("标题：").append(title.trim()).append('\n');
        }
        if (author != null && !author.isBlank()) {
            builder.append("作者：").append(author.trim()).append('\n');
        }
        if (tags != null && !tags.isEmpty()) {
            builder.append("标签：").append(String.join("、", tags)).append('\n');
        }
        if (description != null && !description.isBlank()) {
            builder.append("简介：").append(description.trim()).append('\n');
        }
        return builder.toString().trim();
    }

    /**
     * Strips the HTML Pixiv puts in the work description (links, {@code <br>}, bold) and decodes
     * the handful of entities it actually emits, so the summary reads as plain text.
     */
    static String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = html
                .replaceAll("(?i)<br\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        text = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return text;
    }

    /**
     * Extracts the numeric work id from a Pixiv novel URL. Accepts the query form
     * ({@code show.php?id=}), the path form ({@code /novel/123}), and a bare id.
     * Returns {@code null} when none is present.
     */
    static String extractNovelId(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.matches("\\d+")) {
            return trimmed;
        }
        Matcher queryMatch = ID_FROM_QUERY.matcher(trimmed);
        if (queryMatch.find()) {
            return queryMatch.group(1);
        }
        Matcher pathMatch = ID_FROM_PATH.matcher(trimmed);
        if (pathMatch.find()) {
            return pathMatch.group(1);
        }
        return null;
    }

    /**
     * Turns Pixiv's marked-up novel body into plain text for the translate box.
     *
     * <p>Mirrors the browser extension's {@code pixivTagText()} so both paths read the same
     * way: {@code [newpage]} becomes a blank line (keeping Pixiv's page structure),
     * {@code [[rb:base> reading]]} keeps the base text, formatting tags such as
     * {@code [b:...]} keep their inner text, and control tags such as {@code [jump:N]} or
     * {@code [uploadedimage:N]} are dropped.
     */
    static String cleanPixivText(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String text = content
                .replaceAll("(?i)\\[newpage\\]", "\n\n")
                // Ruby must be handled before the generic [rb:...] rule, otherwise the
                // reading leaks into the text ([[rb:山咲> やまさき]] would become [山咲> やまさき]).
                .replaceAll("(?i)\\[\\[rb:([^>＞,\\]]*)(?:[>＞,][^\\]]*)?\\]\\]", "$1")
                .replaceAll("(?i)\\[rb:([^,\\]]*)[^\\]]*\\]", "$1")
                .replaceAll("\\[\\[[^\\]]*\\]\\]", "")
                .replaceAll("(?i)\\[(?:jump|newpage|[a-z]*image)[^\\]]*\\]", "")
                .replaceAll("(?i)\\[([a-z]+):([^\\]]*)\\]", "$2");

        // Normalise line endings, drop trailing blanks per line, and collapse runs of
        // blank lines so [newpage] does not stack with an existing paragraph break.
        text = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return text;
    }
}
