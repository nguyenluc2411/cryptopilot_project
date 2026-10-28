package com.cryptopilot.market.service;

import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.StoredIndicators;

/**
 * The analysis topics of NSF-05 (D-126); implemented by
 * {@link com.cryptopilot.market.service.impl.AnalysisPushServiceImpl}.
 *
 * <p>Rule: NSF-05; TECHNICAL_DESIGN 9; D-48, D-126.
 */
public interface AnalysisPushService {

    /**
     * Pushes the stored row of a closed candle to its series' analysis topic and components topic, once per candle: a
     * candle already pushed, or one older than the latest pushed of its series, is not pushed again.
     *
     * @param candle the closed candle NSF-05 processed
     * @param row the row NSF-05 stored for it, already committed
     * @return whether the candle was pushed now
     */
    boolean publish(CandleClosed candle, StoredIndicators row);
}
