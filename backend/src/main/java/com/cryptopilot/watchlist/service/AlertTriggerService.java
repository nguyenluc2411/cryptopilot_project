package com.cryptopilot.watchlist.service;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.IndicatorAlertHit;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.PriceAlertHit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the alert evaluation reads and writes in the database (NSF-06): the ACTIVE PRICE alerts the engine holds in
 * memory, the ACTIVE INDICATOR alerts of a closed candle, the ACTIVE FUNDING_RATE and OPEN_INTEREST_CHANGE alerts,
 * and their triggers.
 * Implemented by {@link com.cryptopilot.watchlist.service.impl.AlertTriggerServiceImpl}.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 */
public interface AlertTriggerService {

    /** Every ACTIVE PRICE alert, with its pair and symbol. */
    List<PriceAlert> activePriceAlerts();

    /** The alert as {@link #activePriceAlerts()} gives it, or empty when it is no longer an ACTIVE PRICE alert. */
    Optional<PriceAlert> activePriceAlert(UUID alertId);

    /**
     * Records the trigger if the alert's row still allows it, and publishes {@code AlertTriggered} in the same
     * transaction.
     *
     * @return whether this call recorded the trigger
     */
    boolean tryTrigger(PriceAlertHit hit);

    /** The ACTIVE RSI_14, MACD_CROSS and EMA_CROSS alerts on this candle series. */
    List<IndicatorAlert> activeIndicatorAlerts(UUID pairId, MarketType market, String timeframe);

    /**
     * Records the trigger of an INDICATOR alert if its row still allows it, and publishes {@code AlertTriggered} in the
     * same transaction, a new one even when the caller has one.
     *
     * @return whether this call recorded the trigger
     */
    boolean tryTriggerIndicator(IndicatorAlertHit hit);

    /** Every ACTIVE FUNDING_RATE alert, with its pair. */
    List<IndicatorAlert> activeFundingRateAlerts();

    /**
     * Records the trigger of a FUNDING_RATE alert if its row still allows it, and publishes {@code AlertTriggered} in
     * the same transaction.
     *
     * @return whether this call recorded the trigger
     */
    boolean tryTriggerFundingRate(IndicatorAlertHit hit);

    /** The ACTIVE OPEN_INTEREST_CHANGE alerts on this pair. */
    List<IndicatorAlert> activeOpenInterestAlerts(UUID pairId);

    /**
     * Records the trigger of an OPEN_INTEREST_CHANGE alert on a reading if its row still allows it, and publishes
     * {@code AlertTriggered} in the same transaction, a new one even when the caller has one.
     *
     * @param hit the alert, the change in percent, and the instant of the reading as its bar
     * @return whether this call recorded the trigger
     */
    boolean tryTriggerOpenInterest(IndicatorAlertHit hit);
}
