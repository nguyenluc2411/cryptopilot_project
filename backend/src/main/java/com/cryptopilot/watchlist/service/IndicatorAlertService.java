package com.cryptopilot.watchlist.service;

import com.cryptopilot.market.event.IndicatorsComputed;

/**
 * NSF-06 for indicator alerts: evaluates a closed candle's indicators against the ACTIVE RSI_14, MACD_CROSS and
 * EMA_CROSS alerts of its series. Implemented by
 * {@link com.cryptopilot.watchlist.service.impl.IndicatorAlertServiceImpl}.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9.
 */
public interface IndicatorAlertService {

    /**
     * Evaluates the candle and records the triggers the database allows.
     *
     * @return how many triggers this call recorded
     */
    int evaluate(IndicatorsComputed candle);
}
