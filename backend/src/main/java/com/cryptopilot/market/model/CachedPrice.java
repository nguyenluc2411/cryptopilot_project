package com.cryptopilot.market.model;

import com.cryptopilot.market.MarketType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The latest price of a pair on a market as the cache holds it, always with the instant the exchange stamped it.
 *
 * <p>A Spot entry carries the ticker's last price, best bid and best ask; a futures entry the mark price, index
 * price, predicted funding rate and next funding time. The fields the other market has are absent.
 *
 * <p>Rule: NSF-03; BR-11; TECHNICAL_DESIGN 5.6.
 *
 * @param market the market
 * @param symbol the symbol
 * @param lastPrice the last traded price (Spot)
 * @param bestBid the best bid (Spot)
 * @param bestAsk the best ask (Spot)
 * @param markPrice the mark price (futures)
 * @param indexPrice the index price (futures)
 * @param fundingRate the predicted funding rate (futures)
 * @param nextFundingTime the next settlement, as the source reported it (futures)
 * @param sourceTime the instant the exchange stamped the values
 */
public record CachedPrice(
        MarketType market,
        String symbol,
        BigDecimal lastPrice,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal markPrice,
        BigDecimal indexPrice,
        BigDecimal fundingRate,
        Instant nextFundingTime,
        Instant sourceTime) {}
