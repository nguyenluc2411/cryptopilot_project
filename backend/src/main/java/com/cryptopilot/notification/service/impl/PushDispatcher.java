package com.cryptopilot.notification.service.impl;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.notification.client.PushMessage;
import com.cryptopilot.notification.client.PushResult;
import com.cryptopilot.notification.client.PushSender;
import com.cryptopilot.notification.config.PushProperties;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.watchlist.event.AlertTriggered;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pushes one notification to every active device of its recipient (T-058, NSF-15): only while the account is ACTIVE
 * and its profile keeps push notifications on (SRS 3.2.5). Each device is sent its message on its own:
 *
 * <ul>
 *   <li>delivered — done;
 *   <li>the token is invalid — the device is deactivated (NSF-15) and nothing more is sent to it;
 *   <li>failed for a passing reason (network, 429, 500, 503, 401) — sent again, up to {@code attempts} times in all
 *       (NSF-15: a failed delivery is retried 3 times), the n-th retry waiting {@code retryDelay * 2^(n-1)}, or the
 *       service's {@code Retry-After} when it names one within that bound; a {@code Retry-After} beyond the bound gives
 *       the device up, since retrying sooner than asked would be refused again. Then given up with a warning;
 *   <li>not retryable (a malformed message, a wrong project) — given up at once, the device kept.
 * </ul>
 *
 * <p>The notification stays in the app either way. Called by the outbox listeners after the notification committed;
 * no transaction is held while the service answers.
 *
 * <p>Rule: NSF-15; SRS 3.2.5, 3.4.5.
 *
 * <p>Reference: Google. <i>Firebase Cloud Messaging HTTP v1 API</i>, "ErrorCode" (which errors to retry, with
 * exponential back-off). Fielding, R. et al. (2022). <i>RFC 9110: HTTP Semantics</i>, §10.2.3 "Retry-After".
 */
@Component
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final UserApi users;
    private final EntitlementApi entitlements;
    private final PushSender sender;
    private final PushProperties properties;
    private final Pause pause;

    @Autowired
    public PushDispatcher(UserApi users, EntitlementApi entitlements, PushSender sender, PushProperties properties) {
        this(users, entitlements, sender, properties, Thread::sleep);
    }

    PushDispatcher(
            UserApi users, EntitlementApi entitlements, PushSender sender, PushProperties properties, Pause pause) {
        this.users = users;
        this.entitlements = entitlements;
        this.sender = sender;
        this.properties = properties;
        this.pause = pause;
    }

    /**
     * Pushes an alert that fired, when NSF-15 allows it: the alert asked for a push and the Trader's plan includes
     * external alert channels (BR-62), checked again here since a plan can lapse while the alert stays active. The
     * text is the in-app notification's.
     *
     * @return how many devices it was delivered to
     */
    public int pushAlert(AlertTriggered triggered) {
        if (!triggered.notifyPush() || !entitlements.hasFeature(triggered.userId(), Feature.EXTERNAL_ALERT_CHANNELS)) {
            return 0;
        }
        return push(
                triggered.userId(),
                null,
                NotificationType.ALERT,
                AlertNotices.title(triggered),
                AlertNotices.content(triggered),
                NotificationReference.ALERT,
                triggered.alertId());
    }

    /**
     * Pushes the notification to the recipient's active devices.
     *
     * @param notificationId the in-app notification, or {@code null} when the push is sent on its own (an alert)
     * @return how many devices it was delivered to
     */
    public int push(
            UUID recipientId,
            UUID notificationId,
            NotificationType type,
            String title,
            String content,
            NotificationReference referenceType,
            UUID referenceId) {
        List<String> tokens = users.pushTokens(recipientId);
        if (tokens.isEmpty()) {
            return 0;
        }
        Map<String, String> data = new LinkedHashMap<>();
        if (notificationId != null) {
            data.put("notificationId", notificationId.toString());
        }
        data.put("type", type.name());
        if (referenceType != null) {
            data.put("referenceType", referenceType.name());
        }
        if (referenceId != null) {
            data.put("referenceId", referenceId.toString());
        }
        int delivered = 0;
        for (String token : tokens) {
            if (deliver(new PushMessage(token, title, content, data), recipientId)) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean deliver(PushMessage message, UUID recipientId) {
        Duration backoff = properties.retryDelay();
        for (int attempt = 1; attempt <= properties.attempts(); attempt++) {
            PushResult result = sender.send(message);
            switch (result.outcome()) {
                case DELIVERED -> {
                    return true;
                }
                case INVALID_TOKEN -> {
                    users.deactivateInvalidPushToken(message.token());
                    return false;
                }
                case NON_RETRYABLE -> {
                    return false;
                }
                case FAILED -> {
                    if (attempt == properties.attempts()) {
                        break;
                    }
                    Duration asked = result.retryAfterHint().orElse(backoff);
                    if (asked.compareTo(backoff) > 0) {
                        log.warn(
                                "Push of '{}' to a device of {} given up: the service asked to wait {}",
                                message.title(),
                                recipientId,
                                asked);
                        return false;
                    }
                    if (!sleep(asked)) {
                        return false;
                    }
                    backoff = backoff.multipliedBy(2);
                }
            }
        }
        log.warn(
                "Push of '{}' to a device of {} given up after {} attempts",
                message.title(),
                recipientId,
                properties.attempts());
        return false;
    }

    private boolean sleep(Duration wait) {
        try {
            pause.sleep(wait);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Waits between two attempts; a test passes one that does not wait. */
    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }
}
