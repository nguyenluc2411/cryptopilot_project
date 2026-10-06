package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the {@code market} module tells other modules about a pair: whether it may be traded on a market and with which
 * filters, which markets it is listed on, its current last price and funding rate, its leverage brackets, and its closed 1-minute candles after downtime. Read
 * only; a price that is not current is never returned as one.
 *
 * <p>Rule: BR-07, BR-26, BR-27, BR-29, BR-33; NSF-03, NSF-07; TECHNICAL_DESIGN section 2; A-04.
 */
public interface MarketApi {

    /** The pair, when it is enabled on the market and its filters there are known; otherwise empty. */
    Optional<TradablePair> tradablePair(UUID pairId, MarketType market);

    /** The stored pairs among these ids, listed or not, in no particular order; an unknown id is left out. */
    List<PairListing> pairListings(Collection<UUID> pairIds);

    /** The stored coins with these symbols, e.g. {@code USDT}; an unknown symbol is left out (TR-04). */
    List<CoinListing> coinsBySymbol(Collection<String> symbols);

    /** The stored coins with these keys; an unknown key is left out (TR-04). */
    List<CoinListing> coins(Collection<UUID> coinIds);

    /**
     * The coins with these symbols, storing any not stored yet, so a caller that must hold a balance in a coin (the
     * virtual funds of a paper account, TR-04) never depends on the symbol synchronisation having run. Every symbol
     * asked for is in the result. Joins the caller's transaction, or runs in its own.
     */
    List<CoinListing> ensureCoins(Collection<String> symbols);

    /** The last traded price, when the cache holds a current one; empty when it is missing, old or unreadable. */
    Optional<BigDecimal> currentLastPrice(MarketType market, String symbol);

    /**
     * The current last prices of several pairs of one market, read in one round trip; a pair without a current price
     * is left out.
     */
    Map<String, BigDecimal> currentLastPrices(MarketType market, Collection<String> symbols);

    /**
     * The Futures mark price, when the cache holds a current one; empty when it is missing, old or unreadable. The
     * Futures streams carry the mark price and no last trade price, so this is the current price of a Futures pair.
     */
    Optional<BigDecimal> currentMarkPrice(String symbol);

    /** The predicted funding rate of the coming Futures settlement, when the cache holds a current one. */
    Optional<BigDecimal> currentFundingRate(String symbol);

    /** The pair's Futures leverage brackets, smallest notional first; empty when none are stored. */
    List<LeverageTier> leverageBrackets(UUID pairId);

    /**
     * One page of the pair's closed 1-minute candles that opened at or after {@code from}, oldest first, fetched from
     * the exchange (A-04). An empty page means no closed candle from there on, or a pair no longer enabled on the
     * market. A refusal of the exchange is returned with the instant to ask again, never thrown.
     */
    MinuteKlineBatch closedMinuteKlines(MarketType market, UUID pairId, Instant from);
}
