package com.cryptopilot.notification.client;

/**
 * Sends one push notification (T-058, NSF-15). {@code PushConfig} picks the adapter: Firebase Cloud Messaging when a
 * service account is configured, otherwise a log line.
 *
 * <p>An adapter answers with an outcome and never throws for an answer of the service; the dispatcher decides what to
 * retry and which device to deactivate.
 */
public interface PushSender {

    /**
     * Sends the message.
     *
     * @param message what to send
     * @return what the service answered
     */
    PushResult send(PushMessage message);
}
