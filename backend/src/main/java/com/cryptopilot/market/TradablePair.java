package com.cryptopilot.market;

import com.cryptopilot.market.model.enums.MarketType;
import java.util.Objects;
import java.util.UUID;

/**
 * A pair a plan can be made on: enabled by an administrator on the market (BR-07), with the filters the exchange
 * gives it there.
 *
 * <p>Rule: BR-07, BR-23, BR-30.
 *
 * @param pairId the pair
 * @param symbol the exchange symbol, e.g. {@code BTCUSDT}
 * @param market Spot or Futures
 * @param filters tick size, step size and minimum notional on that market
 * @param exchangeTrading whether the exchange trades it there now, or has not been read yet; {@code false} once the
 *     symbol synchronisation found it NOT_TRADING or DELISTED (NSF-01)
 */
public record TradablePair(
        UUID pairId, String symbol, MarketType market, PairFilters filters, boolean exchangeTrading) {

    public TradablePair {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(filters, "filters");
    }

    /** A pair the exchange trades. */
    public TradablePair(UUID pairId, String symbol, MarketType market, PairFilters filters) {
        this(pairId, symbol, market, filters, true);
    }
}
