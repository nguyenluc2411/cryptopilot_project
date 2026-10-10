package com.cryptopilot.watchlist.service;

import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the alert engine reads and writes in the database (NSF-06): the ACTIVE PRICE alerts it holds in memory, and
 * the triggers it records. Implemented by {@link com.cryptopilot.watchlist.service.impl.AlertTriggerServiceImpl}.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 */
public interface AlertTriggerService {

    /** Every ACTIVE PRICE alert, with its pair and symbol. */
    List<PriceAlert> activePriceAlerts();

    /** The alert as {@link #activePriceAlerts()} gives it, or empty when it is no longer an ACTIVE PRICE alert. */
    Optional<PriceAlert> activePriceAlert(UUID alertId);

    /** How many ACTIVE alerts the engine does not evaluate yet: the INDICATOR alerts. */
    long activeAlertsNotEvaluated();

    /**
     * Records the trigger if the alert's row still allows it, and publishes {@code AlertTriggered} in the same
     * transaction.
     *
     * @return whether this call recorded the trigger
     */
    boolean tryTrigger(PriceAlertHit hit);
}
