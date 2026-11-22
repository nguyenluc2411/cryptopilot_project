package com.cryptopilot.notification.service;

import com.cryptopilot.paper.event.PaperOrderNotice;

/**
 * The notifications of the paper exchange's orders (TR-29): an order that filled, triggered, or was cancelled or
 * expired by the system.
 *
 * <p>Rule: TR-29; NSF-15.
 */
public interface PaperNotificationService {

    /** Writes the in-app notification of the notice; a notice delivered twice notifies once. */
    void orderNotice(PaperOrderNotice notice);
}
