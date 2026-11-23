package com.cryptopilot.notification.client;

import java.util.Map;
import java.util.Objects;

/**
 * One push notification to one installation (NSF-15): what the phone shows, and the data the app opens it with.
 *
 * @param token the installation's messaging token
 * @param title the title, as the in-app notification has it
 * @param body the text
 * @param data the keys the mobile app reads to open the notification's screen: {@code notificationId}, {@code type},
 *     {@code referenceType} and {@code referenceId}; values are strings, as the service requires
 */
public record PushMessage(String token, String title, String body, Map<String, String> data) {

    public PushMessage {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        data = Map.copyOf(Objects.requireNonNull(data, "data"));
    }

    /** The message without its token, for a log line: a token is a credential of the installation. */
    @Override
    public String toString() {
        return "PushMessage(token=***, title=" + title + ", data=" + data + ")";
    }
}
