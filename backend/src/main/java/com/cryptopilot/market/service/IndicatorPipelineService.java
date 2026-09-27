package com.cryptopilot.market.service;

import com.cryptopilot.market.event.CandleClosed;
import com.cryptopilot.market.model.StoredIndicators;
import java.util.Optional;

/**
 * NSF-05: the indicators, support/resistance and component scores of each closed candle, computed once and stored;
 * implemented by {@link com.cryptopilot.market.service.impl.IndicatorPipelineServiceImpl}.
 *
 * <p>Rule: NSF-05; BR-12, BR-13, BR-14; D-48, D-53.
 */
public interface IndicatorPipelineService {

    /**
     * Computes and stores the row of a closed candle, replacing a row already stored for it; empty when the indicators
     * refused the candle as out of order, so there is no row of that candle to write.
     */
    Optional<StoredIndicators> onCandleClosed(CandleClosed candle);
}
