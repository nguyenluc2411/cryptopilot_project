package com.cryptopilot.notification.job;

import com.cryptopilot.notification.model.NotificationWritten;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.service.impl.PushDispatcher;
import com.cryptopilot.watchlist.event.AlertTriggered;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Pushes notifications to the recipients' mobile devices once they have committed (T-058, NSF-15), through the event
 * publication registry, so a push cut short by a restart is sent again. No transaction is held while the Push
 * Notification Service answers.
 *
 * <ul>
 *   <li>Every in-app notification is pushed, as NSF-15 has it, except an ALERT one.
 *   <li>An alert is pushed from its trigger instead, because NSF-15 adds two conditions for it: the alert asked for a
 *       push, and the Trader's plan includes external alert channels (BR-62), checked again here since a plan can
 *       lapse while the alert stays active.
 * </ul>
 *
 * <p>The account's push switch and status are checked by {@link PushDispatcher} for both.
 *
 * <p>Rule: NSF-06, NSF-15, BR-62; SRS 3.2.5, 3.4.4.
 */
@Component
public class PushNotificationListener {

    private final PushDispatcher dispatcher;

    public PushNotificationListener(PushDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void push(NotificationWritten written) {
        if (written.type() == NotificationType.ALERT) {
            return;
        }
        dispatcher.push(
                written.recipientId(),
                written.notificationId(),
                written.type(),
                written.title(),
                written.content(),
                written.referenceType(),
                written.referenceId());
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void pushAlert(AlertTriggered triggered) {
        dispatcher.pushAlert(triggered);
    }
}
