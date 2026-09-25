package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.model.StreamTarget;
import java.util.List;

/**
 * The use cases of {@link com.cryptopilot.market.service.impl.StreamCandleServiceImpl}: the methods called from outside it (D-48).
 *
 * <p>Rule: NSF-03, NSF-02; BR-07, BR-08; TECHNICAL_DESIGN 7.1 steps 3 and 4; A-33.
 */
public interface StreamCandleService {

    /** The pairs to stream on a market: enabled there by an administrator (BR-07) and trading on the exchange. */
    List<StreamTarget> targets(MarketType market);

    /**
     * Stores a closed candle of a stored timeframe, reports the gap before it if there is one, and announces the
     * close. Anything else — a forming candle, a 1m candle — is ignored.
     *
     * <p>Rule: BR-08; NSF-03; TECHNICAL_DESIGN 7.1 steps 3 and 4.
     *
     * @return whether the candle was a stored close (written now or already present)
     */
    boolean onKline(MarketType market, StreamTarget target, KlineMessage message);
}
