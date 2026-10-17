package com.cryptopilot.notification.client;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Sends mail through the SMTP server of {@code spring.mail.*} (T-057), as a multipart message with a plain-text and an
 * HTML alternative.
 *
 * <p>Nothing it logs contains a body or a link: those carry the token.
 *
 * <p>Rule: NSF-15; BR-01, BR-04.
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
        } catch (MessagingException invalid) {
            throw new MailPreparationException("Mail '" + message.subject() + "' could not be built", invalid);
        }
        mailSender.send(mime);
        log.info("Mail '{}' sent", message.subject());
    }
}
