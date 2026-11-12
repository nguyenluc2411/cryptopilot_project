package com.cryptopilot.notification.service;

import com.cryptopilot.watchlist.event.AlertTriggered;

/**
 * Delivers an alert that fired (MSG28) on the channels its Trader chose: always in the app, and by mail when the alert
 * asks for it and the Trader may still receive alerts outside the app. Each trigger is written once however often its
 * event is delivered. Implemented by {@link com.cryptopilot.notification.service.impl.AlertNotificationServiceImpl}.
 *
 * <p>Rule: NSF-06, NSF-15, BR-62 ({@code EXTERNAL_ALERT_CHANNELS}); SRS 3.4.4, 3.4.5.
 */
public interface AlertNotificationService {

    /** Writes the in-app notification of the trigger and pushes it to the Trader's open sessions. */
    void notifyInApp(AlertTriggered event);

    /**
     * Mails the trigger when the alert asks for mail, the Trader's plan still includes external alert channels, and
     * the account has an address that may receive notifications; otherwise sends nothing.
     */
    void mail(AlertTriggered event);
}
