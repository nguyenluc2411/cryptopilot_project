package com.cryptopilot.notification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.notification.RecordingMailSender;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

/**
 * The two mail adapters of T-057: SMTP builds one multipart message with both bodies and never logs the token; the
 * log fallback prints recipient and link on one line. Failures are thrown, so the outbox can retry them.
 *
 * <p>Rule: NSF-15; BR-01, BR-04; A-18.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailSendersTest {

    private static final String TOKEN = "s3cr3t-T0ken_value";

    private static final EmailMessage MAIL = new EmailMessage(
            "jane@example.com",
            "Verify your CryptoPilot email address",
            "Open https://app.test/verify-email?token=" + TOKEN,
            "<a href=\"https://app.test/verify-email?token=" + TOKEN + "\">Verify</a>",
            "https://app.test/verify-email?token=" + TOKEN);

    private final RecordingMailSender smtp = new RecordingMailSender();

    @Test
    void NSF15_smtp_sendsOneMessageWithTextAndHtml(CapturedOutput output) throws Exception {
        new SmtpEmailSender(smtp, "CryptoPilot <no-reply@cryptopilot.test>").send(MAIL);

        assertThat(smtp.sent()).hasSize(1);
        MimeMessage sent = smtp.sent().get(0);
        assertThat(sent.getFrom()[0].toString()).isEqualTo("CryptoPilot <no-reply@cryptopilot.test>");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("jane@example.com");
        assertThat(sent.getSubject()).isEqualTo("Verify your CryptoPilot email address");
        assertThat(RecordingMailSender.partOf(sent, "text/plain")).isEqualTo(MAIL.text());
        assertThat(RecordingMailSender.partOf(sent, "text/html")).isEqualTo(MAIL.html());
        assertThat(output.getAll())
                .contains("Mail 'Verify your CryptoPilot email address' sent")
                .doesNotContain(TOKEN);
    }

    @Test
    void NSF15_smtpUnreachable_throwsSoTheOutboxRetries() {
        smtp.failing(true);

        assertThatExceptionOfType(MailSendException.class)
                .isThrownBy(() -> new SmtpEmailSender(smtp, "no-reply@cryptopilot.test").send(MAIL));
    }

    @Test
    void NSF15_aSenderThatIsNoAddress_failsToBuildTheMessage() {
        assertThatExceptionOfType(MailPreparationException.class)
                .isThrownBy(() -> new SmtpEmailSender(smtp, "not an address <").send(MAIL));
        assertThat(smtp.sent()).isEmpty();
    }

    @Test
    void BR01_theLogFallback_printsRecipientAndLinkOnOneLine(CapturedOutput output) {
        new LoggingEmailSender().send(MAIL);

        assertThat(output.getAll())
                .contains("WARN")
                .contains("to=jane@example.com")
                .contains("link=https://app.test/verify-email?token=" + TOKEN);
    }

    @Test
    void A18_aMessage_printsNeitherItsBodiesNorItsLink() {
        assertThat(MAIL.toString()).doesNotContain(TOKEN).contains("Verify your CryptoPilot email address");
    }
}
