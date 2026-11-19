package com.cryptopilot.notification.service;

import com.cryptopilot.market.event.MarketFallbackUnavailable;
import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketRecoveryOverdue;
import com.cryptopilot.paper.event.PaperIncidentNotice;
import com.cryptopilot.trading.event.PlanIncidentNotice;

/**
 * The in-app notices of a market incident (D-158): to the Traders it affects, when trading on the market is paused and
 * when it reopens, and to the administrators when it is paused. Each is written once however often its event is
 * delivered; a Trader with both paper trading and plans on the market gets one pause notice.
 *
 * <p>Rule: NSF-03; NSF-15; D-158.
 */
public interface MarketNotificationService {

    /** Tells a Trader that paper trading on a market is paused or has reopened, with what the catch-up applied. */
    void paperIncident(PaperIncidentNotice notice);

    /** Tells a Trader that their plans on a market are paused or watched again. */
    void planIncident(PlanIncidentNotice notice);

    /** Tells every active administrator that trading on a market is paused. */
    void pauseAnnounced(MarketPauseAnnounced announced);

    /** Tells every active administrator that a market's recovery needs them. */
    void recoveryOverdue(MarketRecoveryOverdue overdue);

    /** Tells every active administrator that a lost market's fallback prices could not be read either. */
    void fallbackUnavailable(MarketFallbackUnavailable unavailable);
}
