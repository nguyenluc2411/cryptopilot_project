package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the {@code market} module tells other modules about a pair: whether it may be traded on a market and with which
 * filters, which markets it is listed on, its current last price and funding rate, and its leverage brackets. Read
 * only; a price that is not current is never returned as one.
 *
 * <p>Rule: BR-07, BR-26, BR-27, BR-29, BR-33; NSF-03; TECHNICAL_DESIGN section 2.
 */
public interface MarketApi {

    /** The pair, when it is enabled on the market and its filters there are known; otherwise empty. */
    Optional<TradablePair> tradablePair(UUID pairId, MarketType market);

    /** The stored pairs among these ids, listed or not, in no particular order; an unknown id is left out. */
    List<PairListing> pairListings(Collection<UUID> pairIds);

    /** The last traded price, when the cache holds a current one; empty when it is missing, old or unreadable. */
    Optional<BigDecimal> currentLastPrice(MarketType market, String symbol);

    /** The predicted funding rate of the coming Futures settlement, when the cache holds a current one. */
    Optional<BigDecimal> currentFundingRate(String symbol);

    /** The pair's Futures leverage brackets, smallest notional first; empty when none are stored. */
    List<LeverageTier> leverageBrackets(UUID pairId);
}
