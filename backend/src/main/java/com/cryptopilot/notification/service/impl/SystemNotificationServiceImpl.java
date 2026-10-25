package com.cryptopilot.notification.service.impl;

import com.cryptopilot.news.event.NewsSourceFailing;
import com.cryptopilot.notification.model.NewNotification;
import com.cryptopilot.notification.model.enums.NotificationReference;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.SystemNotificationService;
import com.cryptopilot.user.UserApi;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The texts and recipients of the notices to administrators. The SRS has no message for them, so the texts are written
 * here in the style of MSG22 to MSG28: one short sentence naming what happened. The source key is the crawl log entry
 * of the failure and the recipient, so a delivery repeated by the outbox writes nothing, and a later run of failures,
 * which has another entry, is told again.
 *
 * <p>Rule: NSF-10; NSF-15.
 */
@Service
@RequiredArgsConstructor
public class SystemNotificationServiceImpl implements SystemNotificationService {

    /** The longest error quoted in a notice; the crawl log on SCR-41 keeps the rest. */
    static final int MAX_ERROR_LENGTH = 300;

    private final NotificationWriter writer;
    private final UserApi users;

    @Override
    @Transactional
    public void newsSourceFailing(NewsSourceFailing event) {
        String error = event.error() == null ? "" : event.error();
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH) + "…";
        }
        String content = event.sourceName() + " could not be read " + event.consecutiveFailures()
                + " times in a row. Last error: " + error;
        for (UUID admin : users.activeAdministrators()) {
            writer.write(new NewNotification(
                    admin,
                    NotificationType.SYSTEM,
                    "A news source keeps failing",
                    content,
                    NotificationReference.NEWS_SOURCE,
                    event.sourceId(),
                    "NEWS_SOURCE_FAILING:" + event.crawlLogId() + ":" + admin));
        }
    }
}
