package com.cryptopilot.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.auth.event.PasswordResetTokenIssued;
import com.cryptopilot.auth.event.VerificationTokenIssued;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The verification and reset mails (T-057): the link opens the web route that reads {@code token}, the token is
 * URL-encoded, the text says when the link expires, and the HTML is escaped.
 *
 * <p>Rule: BR-01, BR-04; SRS UC-01, UC-04.
 */
class AccountLinkMailsTest {

    private static final UUID USER = UUID.fromString("01a14a40-703a-74e7-bb70-8c0b6189358f");

    private static final Instant EXPIRES = Instant.parse("2026-10-18T09:30:00Z");

    @Test
    void BR01_theVerificationMail_linksToTheVerifyScreenWithTheToken() {
        EmailMessage mail = mails("https://app.example.com")
                .verification(new VerificationTokenIssued(USER, "jane@example.com", "abc_DEF-123", EXPIRES));

        assertThat(mail.to()).isEqualTo("jane@example.com");
        assertThat(mail.subject()).isEqualTo("Verify your CryptoPilot email address");
        assertThat(mail.actionLink()).isEqualTo("https://app.example.com/verify-email?token=abc_DEF-123");
        assertThat(mail.text())
                .contains("https://app.example.com/verify-email?token=abc_DEF-123")
                .contains("expires at 2026-10-18 09:30 UTC");
        assertThat(mail.html())
                .contains("<a href=\"https://app.example.com/verify-email?token=abc_DEF-123\">")
                .contains("expires at 2026-10-18 09:30 UTC");
    }

    @Test
    void BR04_theResetMail_linksToTheResetScreenAndSaysIgnoringIsSafe() {
        EmailMessage mail = mails("https://app.example.com")
                .passwordReset(new PasswordResetTokenIssued(USER, "jane@example.com", "xyz", EXPIRES));

        assertThat(mail.subject()).isEqualTo("Reset your CryptoPilot password");
        assertThat(mail.actionLink()).isEqualTo("https://app.example.com/reset-password?token=xyz");
        assertThat(mail.text()).contains("expires at 2026-10-18 09:30 UTC").contains("your password stays as it is");
        assertThat(mail.html()).contains("<a href=\"https://app.example.com/reset-password?token=xyz\">");
    }

    @Test
    void BR01_aTokenWithReservedCharacters_isUrlEncodedAndHtmlEscaped() {
        EmailMessage mail = mails("https://app.example.com")
                .verification(new VerificationTokenIssued(USER, "jane@example.com", "a+b/c=&d", EXPIRES));

        assertThat(mail.actionLink()).isEqualTo("https://app.example.com/verify-email?token=a%2Bb%2Fc%3D%26d");
        assertThat(mail.html()).contains("href=\"https://app.example.com/verify-email?token=a%2Bb%2Fc%3D%26d\"");
    }

    @Test
    void BR01_aBaseUrlWithAPathOrATrailingSlash_keepsOneSlash() {
        VerificationTokenIssued issued = new VerificationTokenIssued(USER, "jane@example.com", "t", EXPIRES);

        assertThat(mails("http://localhost:5173/").verification(issued).actionLink())
                .isEqualTo("http://localhost:5173/verify-email?token=t");
        assertThat(mails("https://example.com/app").verification(issued).actionLink())
                .isEqualTo("https://example.com/app/verify-email?token=t");
    }

    private static AccountLinkMails mails(String baseUrl) {
        return new AccountLinkMails(new MailDeliveryProperties("no-reply@example.com", URI.create(baseUrl)));
    }
}
