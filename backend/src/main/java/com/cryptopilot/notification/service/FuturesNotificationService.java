package com.cryptopilot.notification.service;

import com.cryptopilot.paper.event.FuturesRiskNotice;

/**
 * The in-app notices of a Trader's paper Futures risk: a margin call and a liquidation, each told once however often
 * the event is delivered. Implemented by
 * {@link com.cryptopilot.notification.service.impl.FuturesNotificationServiceImpl}.
 *
 * <p>Rule: TR-43; NSF-15.
 */
public interface FuturesNotificationService {

    /** Tells the Trader about the margin call or the liquidation. */
    void riskNotice(FuturesRiskNotice notice);
}
