package com.cryptopilot.notification.service;

import com.cryptopilot.news.event.NewsSourceFailing;

/**
 * The in-app notices of faults only an administrator can act on: each active administrator is told, once however
 * often the event is delivered. Implemented by
 * {@link com.cryptopilot.notification.service.impl.SystemNotificationServiceImpl}.
 *
 * <p>Rule: NSF-10 (after five failures in a row of a source, the administrators are notified); NSF-15.
 */
public interface SystemNotificationService {

    /** Tells every active administrator that a news source keeps failing, naming it and the last error. */
    void newsSourceFailing(NewsSourceFailing event);
}
