package com.cryptopilot.market.service;

import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.UUID;

/**
 * The closed 1-minute candles a restarted matching engine replays (A-04).
 *
 * <p>Rule: NSF-07; TECHNICAL_DESIGN 7.7; A-04.
 */
public interface MinuteKlineService {

    /** See {@link com.cryptopilot.market.MarketApi#closedMinuteKlines}. */
    MinuteKlineBatch closedMinuteKlines(MarketType market, UUID pairId, Instant from);
}
