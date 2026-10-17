package com.cryptopilot.notification.client;

import java.util.Objects;

/**
 * One mail to one recipient, in plain text and HTML.
 *
 * @param to the recipient's address
 * @param subject the subject line
 * @param text the plain-text body
 * @param html the HTML body, saying the same as {@code text}
 * @param actionLink the one link the mail asks the reader to open; what the log fallback prints in place of the mail
 */
public record EmailMessage(String to, String subject, String text, String html, String actionLink) {

    public EmailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(actionLink, "actionLink");
    }

    /** The bodies and the link carry a token, so a log line shows the subject only. */
    @Override
    public String toString() {
        return "EmailMessage[subject=" + subject + "]";
    }
}
