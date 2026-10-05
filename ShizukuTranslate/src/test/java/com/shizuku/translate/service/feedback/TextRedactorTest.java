package com.shizuku.translate.service.feedback;

import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redaction is the pipeline's privacy red line: everything must be scrubbed before it is
 * persisted. These tests cover each rule, the order-sensitive combinations, the custom term
 * list, and a combined text that must not leak any of its secrets.
 */
class TextRedactorTest {

    private TextRedactor redactor;

    @BeforeEach
    void setUp() {
        FeedbackProperties properties = new FeedbackProperties();
        redactor = new TextRedactor(properties);
    }

    @Test
    void redactsEmailAddresses() {
        String result = redactor.redact("联系我 foo.bar+baz@example.co.uk 谢谢");
        assertEquals("联系我 <EMAIL> 谢谢", result);
    }

    @Test
    void redactsChineseMobileNumbers() {
        assertEquals("电话 <PHONE> 结束", redactor.redact("电话 13812345678 结束"));
    }

    @Test
    void redactsInternationalNumbers() {
        assertEquals("call <PHONE> now", redactor.redact("call +8613812345678 now"));
    }

    @Test
    void redactsChineseIdCards() {
        assertEquals("身份证 <ID> 验证", redactor.redact("身份证 11010119900307721X 验证"));
        assertEquals("<ID>", redactor.redact("110101199003077218"));
    }

    @Test
    void redactsBankCardNumbers() {
        assertEquals("卡号 <CARD>", redactor.redact("卡号 6222021234567890123"));
    }

    @Test
    void keepsShortDigitRuns() {
        // Chapter numbers, timestamps (13 digits) and novel ids must survive: only 16-19
        // digit runs are treated as cards.
        assertEquals("第 123 章，时间 1699999999999", redactor.redact("第 123 章，时间 1699999999999"));
    }

    @Test
    void redactsUrlQueryValuesButKeepsShape() {
        String result = redactor.redact("打开 https://example.com/reset?token=abc123&id=42 重置");
        assertEquals("打开 https://example.com/reset?token=<REDACTED>&id=<REDACTED> 重置", result);
    }

    @Test
    void keepsUrlsWithoutQuery() {
        assertEquals("看 https://example.com/a/b 页", redactor.redact("看 https://example.com/a/b 页"));
    }

    @Test
    void redactsMentions() {
        assertEquals("谢谢 <MENTION>", redactor.redact("谢谢 @someuser"));
        assertEquals("支持 <MENTION> 哦", redactor.redact("支持 @张三 哦"));
    }

    @Test
    void redactsExtraTerms() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.getRedaction().setExtraTerms(List.of("秘密项目"));
        TextRedactor custom = new TextRedactor(properties);
        assertEquals("这是 <TERM> 的内容", custom.redact("这是 秘密项目 的内容"));
    }

    @Test
    void plainProseIsUntouched() {
        String text = "彼女は静かに微笑んで、窓の外の雪を見つめていた。\n「またね」と小さく呟いた。";
        assertEquals(text, redactor.redact(text));
    }

    @Test
    void combinedTextLeaksNothing() {
        String text = "用户 foo@bar.com 手机 13912345678 卡号 6222021234567890123 "
                + "身份证 110101199003077218 主页 https://x.cn/p?token=zzz&key=yyy 联系 @admin";
        String result = redactor.redact(text);
        for (String secret : new String[]{"foo@bar.com", "13912345678", "6222021234567890123",
                "110101199003077218", "zzz", "yyy", "@admin"}) {
            assertFalse(result.contains(secret), "泄漏了敏感串: " + secret);
        }
        assertTrue(result.contains("<EMAIL>"));
        assertTrue(result.contains("<PHONE>"));
        assertTrue(result.contains("<CARD>"));
        assertTrue(result.contains("<ID>"));
        assertTrue(result.contains("<REDACTED>"));
        assertTrue(result.contains("<MENTION>"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals(null, redactor.redact(null));
        assertEquals("", redactor.redact(""));
    }

    @Test
    void disabledRedactionIsPassThrough() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.getRedaction().setEnabled(false);
        TextRedactor disabled = new TextRedactor(properties);
        assertEquals("mail a@b.com", disabled.redact("mail a@b.com"));
    }
}
