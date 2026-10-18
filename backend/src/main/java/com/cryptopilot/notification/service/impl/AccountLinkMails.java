package com.cryptopilot.notification.service.impl;

import com.cryptopilot.auth.event.PasswordResetTokenIssued;
import com.cryptopilot.auth.event.VerificationTokenIssued;
import com.cryptopilot.notification.client.EmailMessage;
import com.cryptopilot.notification.config.MailDeliveryProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Writes the verification and password reset mails (T-057): the link to the web screen that consumes the token and a
 * short text around it, in plain text and HTML.
 *
 * <p>The link is {@code PUBLIC_BASE_URL} + the web route + {@code ?token=}, the query parameter SCR-03 and SCR-06 read;
 * the token goes only into that link and is URL-encoded. The text states when the link expires and, for a reset, that
 * ignoring the mail leaves the password unchanged.
 *
 * <p>Rule: BR-01, BR-04; SRS UC-01, UC-04.
 *
 * <p>Reference: OWASP Foundation. <i>Forgot Password Cheat Sheet</i> (a single-use, expiring token sent only inside
 * the link to the user's address).
 */
@Component
public class AccountLinkMails {

    static final String VERIFY_EMAIL_ROUTE = "/verify-email";
    static final String RESET_PASSWORD_ROUTE = "/reset-password";

    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final MailDeliveryProperties properties;

    public AccountLinkMails(MailDeliveryProperties properties) {
        this.properties = properties;
    }

    /** The mail that carries the address verification link (UC-01). */
    public EmailMessage verification(VerificationTokenIssued issued) {
        String link = link(VERIFY_EMAIL_ROUTE, issued.token());
        String expiry = EXPIRY.format(issued.expiresAt());
        String text = """
                Welcome to CryptoPilot.

                Open this link to verify your email address:
                %s

                The link works once and expires at %s. If it has expired, ask for a new one from the sign-in page.

                If you did not create a CryptoPilot account, ignore this email.
                """.formatted(link, expiry);
        String html = html(
                "Welcome to CryptoPilot.",
                link,
                "Verify my email address",
                "The link works once and expires at " + expiry
                        + ". If it has expired, ask for a new one from the sign-in page.",
                "If you did not create a CryptoPilot account, ignore this email.");
        return new EmailMessage(issued.email(), "Verify your CryptoPilot email address", text, html, link);
    }

    /** The mail that carries the password reset link (UC-04). */
    public EmailMessage passwordReset(PasswordResetTokenIssued issued) {
        String link = link(RESET_PASSWORD_ROUTE, issued.token());
        String expiry = EXPIRY.format(issued.expiresAt());
        String text = """
                Someone asked to reset the password of the CryptoPilot account for this address.

                Open this link to choose a new password:
                %s

                The link works once and expires at %s. Resetting signs you out on every device.

                If you did not ask for this, ignore this email: your password stays as it is.
                """.formatted(link, expiry);
        String html = html(
                "Someone asked to reset the password of the CryptoPilot account for this address.",
                link,
                "Choose a new password",
                "The link works once and expires at " + expiry + ". Resetting signs you out on every device.",
                "If you did not ask for this, ignore this email: your password stays as it is.");
        return new EmailMessage(issued.email(), "Reset your CryptoPilot password", text, html, link);
    }

    private String link(String route, String token) {
        return UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .path(route)
                .queryParam("token", URLEncoder.encode(token, StandardCharsets.UTF_8))
                .build(true)
                .toUriString();
    }

    private static String html(String intro, String link, String action, String expiry, String ignore) {
        return """
                <!DOCTYPE html>
                <html><body style="font-family: Arial, sans-serif; line-height: 1.5">
                <p>%s</p>
                <p><a href="%s">%s</a></p>
                <p>%s</p>
                <p>%s</p>
                </body></html>
                """.formatted(
                        HtmlUtils.htmlEscape(intro),
                        HtmlUtils.htmlEscape(link),
                        HtmlUtils.htmlEscape(action),
                        HtmlUtils.htmlEscape(expiry),
                        HtmlUtils.htmlEscape(ignore));
    }
}
