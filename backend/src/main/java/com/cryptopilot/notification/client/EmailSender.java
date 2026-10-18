package com.cryptopilot.notification.client;

/**
 * Sends a mail (T-057). {@code MailConfig} picks the adapter: SMTP when a host is configured, otherwise a log line.
 *
 * <p>A failure is thrown, never swallowed: the caller is an outbox listener, and an exception is what keeps its event
 * publication unfinished so that the outbox processor retries it.
 */
public interface EmailSender {

    /**
     * Sends the message.
     *
     * @param message what to send
     * @throws org.springframework.mail.MailException when the mail could not be handed to the server
     */
    void send(EmailMessage message);
}
