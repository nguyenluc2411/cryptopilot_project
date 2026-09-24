package com.cryptopilot.market.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One message of a Binance market stream, in the client's own types.
 *
 * <p>The three kinds NSF-03 subscribes to and nothing else. Every price, rate and volume is a {@link BigDecimal}
 * parsed from the exchange's string, as on the REST side (TECHNICAL_DESIGN 5.4); times are UTC instants.
 *
 * <p>Rule: NSF-03; BR-08, BR-09; TECHNICAL_DESIGN 7.1.
 */
public sealed interface StreamMessage {

    /** The symbol the message is about, as the exchange spells it, e.g. {@code BTCUSDT}. */
    String symbol();

    /** When the exchange produced the message. */
    Instant eventTime();

    /**
     * A candle update, {@code <symbol>@kline_<interval>}: the forming candle about once a second and, once, the
     * same candle marked closed. Only the closed one may be stored (BR-08).
     *
     * @param symbol the pair
     * @param interval the candle's timeframe
     * @param kline the candle as it stands
     * @param closed whether the exchange marks the candle closed ({@code "x": true})
     * @param eventTime when the exchange produced the message
     */
    record KlineMessage(String symbol, MarketInterval interval, Kline kline, boolean closed, Instant eventTime)
            implements StreamMessage {}

    /**
     * The Spot rolling 24-hour ticker, {@code <symbol>@ticker}, once a second.
     *
     * @param symbol the pair
     * @param lastPrice the last traded price
     * @param bestBidPrice the best bid
     * @param bestAskPrice the best ask
     * @param highPrice24h the highest price of the last 24 hours
     * @param lowPrice24h the lowest price of the last 24 hours
     * @param priceChangePercent24h the change of the last 24 hours, in percent
     * @param baseVolume24h the base-asset volume of the last 24 hours
     * @param quoteVolume24h the quote-asset volume of the last 24 hours
     * @param eventTime when the exchange produced the message
     */
    record TickerMessage(
            String symbol,
            BigDecimal lastPrice,
            BigDecimal bestBidPrice,
            BigDecimal bestAskPrice,
            BigDecimal highPrice24h,
            BigDecimal lowPrice24h,
            BigDecimal priceChangePercent24h,
            BigDecimal baseVolume24h,
            BigDecimal quoteVolume24h,
            Instant eventTime)
            implements StreamMessage {}

    /**
     * The futures mark price, {@code <symbol>@markPrice@1s}, once a second.
     *
     * @param symbol the pair
     * @param markPrice the mark price
     * @param indexPrice the index price
     * @param fundingRate the predicted funding rate of the coming settlement, not a settled one (NSF-04 stores
     *     those)
     * @param nextFundingTime the next settlement, as the exchange reports it
     * @param eventTime when the exchange produced the message
     */
    record MarkPriceMessage(
            String symbol,
            BigDecimal markPrice,
            BigDecimal indexPrice,
            BigDecimal fundingRate,
            Instant nextFundingTime,
            Instant eventTime)
            implements StreamMessage {}
}
