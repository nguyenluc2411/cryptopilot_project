package com.cryptopilot.notification.config;

import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.client.LoggingEmailSender;
import com.cryptopilot.notification.client.SmtpEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * Chooses the mail adapter at start-up (T-057): SMTP when {@code spring.mail.host} ({@code SMTP_HOST}) is set, a log
 * line otherwise. The prod profile has no fallback and refuses to start without a host, because a production account
 * that cannot receive its verification link cannot sign in (MSG11), and the log line would hold working tokens.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailDeliveryProperties.class)
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    EmailSender emailSender(
            @Value("${spring.mail.host:}") String host,
            ObjectProvider<JavaMailSender> mailSender,
            MailDeliveryProperties properties,
            Environment environment) {
        if (StringUtils.hasText(host)) {
            log.info("Mail goes out through SMTP host {}", host);
            return new SmtpEmailSender(mailSender.getObject(), properties.from());
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "SMTP_HOST is not set: the prod profile sends verification and password reset mail and has no"
                            + " fallback. Set SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD and SMTP_FROM.");
        }
        log.warn("SMTP_HOST is not set: mail is not sent, its links are logged instead");
        return new LoggingEmailSender();
    }
}
