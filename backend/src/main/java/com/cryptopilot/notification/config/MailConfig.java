package com.cryptopilot.notification.config;

import com.cryptopilot.notification.client.EmailSender;
import com.cryptopilot.notification.client.LoggingEmailSender;
import com.cryptopilot.notification.client.SmtpEmailSender;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
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
 *
 * <p>The SMTP adapter also refuses a sender that is not an address. An unset {@code SMTP_FROM} in the prod profile
 * reaches this class as the literal {@code ${SMTP_FROM}}, which would otherwise be sent as the envelope sender. The
 * connection itself requires STARTTLS ({@code spring.mail.properties} in {@code application.yml}).
 *
 * <p>Reference: OWASP Foundation. <i>Transport Layer Security Cheat Sheet</i> (require TLS rather than fall back to
 * plaintext when it is not offered).
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
            return new SmtpEmailSender(mailSender.getObject(), senderAddress(properties.from()));
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "SMTP_HOST is not set: the prod profile sends verification and password reset mail and has no"
                            + " fallback. Set SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD and SMTP_FROM.");
        }
        log.warn("SMTP_HOST is not set: mail is not sent, its links are logged instead");
        return new LoggingEmailSender();
    }

    /** The configured sender, checked to be one address with a local part and a domain. */
    static String senderAddress(String from) {
        try {
            String address = new InternetAddress(from, true).getAddress();
            int at = address.indexOf('@');
            if (at > 0 && at < address.length() - 1) {
                return from;
            }
        } catch (AddressException invalid) {
            // Reported below with the value that was configured.
        }
        throw new IllegalStateException("SMTP_FROM is not a valid sender address: '" + from
                + "'. Set SMTP_FROM to the address mail is sent from, e.g. no-reply@example.com.");
    }
}
