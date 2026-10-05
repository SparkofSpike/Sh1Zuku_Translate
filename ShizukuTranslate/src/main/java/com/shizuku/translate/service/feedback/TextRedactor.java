package com.shizuku.translate.service.feedback;

import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort PII scrubbing for the feedback pipeline. Every text field is redacted here
 * <em>before</em> anything is persisted — samples, event payloads and edits all pass through
 * {@link #redact(String)} in the writer thread, never at export time.
 *
 * <p>Rules are deliberately conservative so normal prose keeps its shape: the text is
 * structure-preserving (placeholders replace values in place, nothing is dropped). Order
 * matters: URLs go first (their query values are neutralised before other rules see them),
 * then e-mail addresses, then the 18-digit Chinese ID before the generic card rule, so an
 * ID number is never swallowed by the card pattern.
 *
 * <p>Bank card rule covers 16-19 digit runs only. Shorter digit runs (13-15) are left alone
 * because they collide with timestamps, novel IDs and chapter numbers in ordinary translation
 * text far more often than they are actual cards; the loss is acceptable for this dataset.
 */
@Component
public class TextRedactor {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile(
            "\\b[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b");
    private static final Pattern PHONE_CN = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern PHONE_INTL = Pattern.compile("\\+\\d{7,15}\\b");
    private static final Pattern CARD = Pattern.compile("\\b\\d{16,19}\\b");
    private static final Pattern MENTION = Pattern.compile("@[\\p{L}\\p{N}_]{2,30}");
    /** One query parameter inside a URL: keeps the key, redacts the value. */
    private static final Pattern QUERY_VALUE = Pattern.compile("([?&][^=&#\\s]+=)([^&#\\s]*)");

    private final FeedbackProperties properties;

    public TextRedactor(FeedbackProperties properties) {
        this.properties = properties;
    }

    /** Returns the text with sensitive values replaced by placeholders; null-safe. */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (!properties.getRedaction().isEnabled()) {
            return text;
        }
        String result = replaceUrls(text);
        result = EMAIL.matcher(result).replaceAll("<EMAIL>");
        result = ID_CARD.matcher(result).replaceAll("<ID>");
        result = PHONE_CN.matcher(result).replaceAll("<PHONE>");
        result = PHONE_INTL.matcher(result).replaceAll("<PHONE>");
        result = CARD.matcher(result).replaceAll("<CARD>");
        result = MENTION.matcher(result).replaceAll("<MENTION>");
        for (String term : properties.getRedaction().getExtraTerms()) {
            if (term != null && !term.isBlank()) {
                result = result.replace(term, "<TERM>");
            }
        }
        return result;
    }

    /** Keeps the URL shape but neutralises every query value (tokens, ids, signatures). */
    private static String redactUrl(String url) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return url;
        }
        String head = url.substring(0, queryStart);
        String query = url.substring(queryStart);
        return head + QUERY_VALUE.matcher(query).replaceAll("$1<REDACTED>");
    }

    private static String replaceUrls(String text) {
        Matcher matcher = URL.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        int last = 0;
        do {
            result.append(text, last, matcher.start());
            result.append(redactUrl(matcher.group()));
            last = matcher.end();
        } while (matcher.find());
        result.append(text, last, text.length());
        return result.toString();
    }
}
