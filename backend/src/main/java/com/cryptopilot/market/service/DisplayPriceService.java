package com.cryptopilot.market.service;

import com.cryptopilot.market.dto.response.FallbackCandleResponse;
import com.cryptopilot.market.dto.response.FallbackPricesResponse;
import com.cryptopilot.market.model.enums.DisplaySource;
import com.cryptopilot.market.model.enums.MarketType;
import java.util.List;

/**
 * The display-only fallback prices of a market whose exchange data is lost (D-159): read from the fallback source
 * while the market is paused or recovering, held in memory, shown labelled with their source.
 *
 * <p>Isolated on purpose (I1): nothing that fills, triggers, liquidates, charges or alerts reads them. They never reach
 * the market's API to the other modules, its price cache or its stored candles; an architecture test keeps it so.
 *
 * <p>Rule: BR-09; NSF-03; D-159.
 */
public interface DisplayPriceService {

    /** Reads the market's fallback prices again; a failure leaves none and is told to the administrators once. */
    void refresh(MarketType market);

    /** Forgets the market's fallback prices, once its exchange data is back. */
    void clear(MarketType market);

    /** The market's fallback prices as last read, with where the market's displayed prices come from now. */
    FallbackPricesResponse prices(MarketType market);

    /** Where the market's displayed prices come from now. */
    DisplaySource source(MarketType market);

    /**
     * The latest fallback one-minute candles of a pair, oldest first, while its market's exchange data is lost; none
     * otherwise, or for a pair the fallback does not list.
     *
     * @param limit 1 to 300
     */
    List<FallbackCandleResponse> candles(MarketType market, String symbol, int limit);
}
