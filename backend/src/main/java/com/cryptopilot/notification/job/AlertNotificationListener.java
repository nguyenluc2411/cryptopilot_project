package com.cryptopilot.notification.job;

import com.cryptopilot.notification.service.AlertNotificationService;
import com.cryptopilot.watchlist.event.AlertTriggered;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Delivers each alert that fired (NSF-06) once its trigger has committed. The in-app notification and the mail are two
 * listeners, so each is recorded and retried on its own: a mail server that is down never holds back the notification
 * in the app, and a notification already written is not written again when the mail is retried.
 *
 * <p>Rule: NSF-06, NSF-15; SRS 3.4.4.
 */
@Component
public class AlertNotificationListener {

    private final AlertNotificationService notifications;

    public AlertNotificationListener(AlertNotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void notifyInApp(AlertTriggered triggered) {
        notifications.notifyInApp(triggered);
    }

    // No database work of its own, so no transaction: no pooled connection is held while the SMTP server answers.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void mail(AlertTriggered triggered) {
        notifications.mail(triggered);
    }
}
