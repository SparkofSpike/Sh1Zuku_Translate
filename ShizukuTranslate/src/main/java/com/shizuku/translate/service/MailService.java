package com.shizuku.translate.service;

import com.shizuku.translate.config.MailConfig.MailProperties;
import com.shizuku.translate.exception.BusinessException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Thin wrapper around JavaMailSender for verification-code emails.
 * Never logs the code or the SMTP password.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties props;

    public MailService(JavaMailSender mailSender, MailProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    public void sendVerificationCode(String toEmail, String code) {
        if (!props.isConfigured()) {
            throw new BusinessException("邮件服务未配置，暂时无法发送验证码，请联系管理员");
        }
        String from = StringUtils.hasText(props.getFrom()) ? props.getFrom() : props.getUsername();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject("【Shizuku翻译】邮箱验证码");
            helper.setText("你的邮箱验证码是：" + code
                    + "\n\n验证码 10 分钟内有效，请勿泄露给他人。"
                    + "\n如果这不是你本人的操作，请忽略本邮件。", false);
            mailSender.send(message);
            log.info("Verification email sent to {}", toEmail);
        } catch (MailException | MessagingException e) {
            log.error("Failed to send verification email to {}", toEmail, e);
            throw new BusinessException("验证码发送失败，请稍后重试或联系管理员");
        }
    }
}
