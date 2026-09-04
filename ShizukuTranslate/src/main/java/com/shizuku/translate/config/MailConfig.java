package com.shizuku.translate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * SMTP configuration for verification-code emails.
 *
 * <p>The backend must keep working when no mail server is configured
 * (local development, misconfigured deployment), so the JavaMailSender
 * bean is always created. MailService checks {@link MailProperties#isConfigured()}
 * before sending and reports a clear error instead of failing at startup.</p>
 */
@Configuration
public class MailConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.mail")
    public MailProperties mailProperties() {
        return new MailProperties();
    }

    @Bean
    public JavaMailSender javaMailSender(MailProperties props) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        if (!props.isConfigured()) {
            return sender;
        }
        sender.setHost(props.getHost());
        sender.setPort(props.getPort());
        if (StringUtils.hasText(props.getUsername())) {
            sender.setUsername(props.getUsername());
        }
        if (StringUtils.hasText(props.getPassword())) {
            sender.setPassword(props.getPassword());
        }
        Properties javaProps = sender.getJavaMailProperties();
        javaProps.put("mail.transport.protocol", "smtp");
        javaProps.put("mail.smtp.auth", "true");
        if (props.isSsl()) {
            javaProps.put("mail.smtp.ssl.enable", "true");
        } else {
            javaProps.put("mail.smtp.starttls.enable", "true");
        }
        javaProps.put("mail.smtp.connectiontimeout", "10000");
        javaProps.put("mail.smtp.timeout", "15000");
        javaProps.put("mail.smtp.writetimeout", "15000");
        return sender;
    }

    public static class MailProperties {
        private String host;
        private int port = 465;
        private String username;
        private String password;
        private String from;
        private boolean ssl = true;

        public boolean isConfigured() {
            return StringUtils.hasText(host);
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public boolean isSsl() { return ssl; }
        public void setSsl(boolean ssl) { this.ssl = ssl; }
    }
}
