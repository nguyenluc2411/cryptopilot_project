package com.cryptopilot.notification.job;

import com.cryptopilot.notification.service.PaperNotificationService;
import com.cryptopilot.paper.event.PaperOrderNotice;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Writes the notification of each paper order that ended in a way its Trader is told about (TR-29), once the change
 * has committed, through the event publication registry, in a transaction of its own.
 *
 * <p>Rule: TR-29; NSF-15.
 */
@Component
public class PaperNotificationListener {

    private final PaperNotificationService notifications;

    public PaperNotificationListener(PaperNotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void on(PaperOrderNotice notice) {
        notifications.orderNotice(notice);
    }
}
