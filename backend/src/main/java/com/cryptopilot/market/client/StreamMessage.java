package com.cryptopilot.market.client;

import com.cryptopilot.market.model.enums.MarketInterval;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One message of a Binance market stream, in the client's own types.
 *
 * <p>The three kinds NSF-03 subscribes to, and the three of the trading screen and the paper engine (TR-03), and
 * nothing else. Every price, rate and volume is a {@link BigDecimal}
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
     * The rolling 24-hour ticker, {@code <symbol>@ticker}, once a second, on Spot and on futures.
     *
     * @param symbol the pair
     * @param lastPrice the last traded price
     * @param bestBidPrice the best bid; {@code null} on futures, whose ticker does not carry it
     * @param bestAskPrice the best ask; {@code null} on futures
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

    /**
     * One aggregated trade, {@code <symbol>@aggTrade}: the trades of one taker order at one price, pushed as they
     * happen. The paper engine fills resting orders on it and the trading screen lists it (TR-03).
     *
     * @param symbol the pair
     * @param aggregateTradeId the exchange's id of the aggregated trade, increasing per pair
     * @param price the traded price
     * @param quantity the traded base quantity
     * @param tradeTime when the trade happened
     * @param buyerMaker whether the buyer was the maker, so the taker sold
     * @param eventTime when the exchange produced the message
     */
    record AggTradeMessage(
            String symbol,
            long aggregateTradeId,
            BigDecimal price,
            BigDecimal quantity,
            Instant tradeTime,
            boolean buyerMaker,
            Instant eventTime)
            implements StreamMessage {}

    /**
     * The best bid and ask, {@code <symbol>@bookTicker}, pushed whenever either changes. The Spot payload carries no
     * event time, so {@code eventTime} is when this server read it (TR-03).
     *
     * @param symbol the pair
     * @param updateId the order book update id the values belong to
     * @param bidPrice the best bid
     * @param bidQuantity the quantity at the best bid
     * @param askPrice the best ask
     * @param askQuantity the quantity at the best ask
     * @param eventTime when this server read the message
     */
    record BookTickerMessage(
            String symbol,
            long updateId,
            BigDecimal bidPrice,
            BigDecimal bidQuantity,
            BigDecimal askPrice,
            BigDecimal askQuantity,
            Instant eventTime)
            implements StreamMessage {}

    /**
     * The top levels of the order book, {@code <symbol>@depth<levels>@100ms}: a full snapshot of those levels each time,
     * best first. The Spot payload carries neither the symbol nor an event time, so the symbol comes from the stream
     * name and {@code eventTime} is when this server read it (TR-03).
     *
     * @param symbol the pair
     * @param lastUpdateId the order book update id of the snapshot
     * @param bids the bids, highest price first
     * @param asks the asks, lowest price first
     * @param eventTime when this server read the message
     */
    record DepthMessage(String symbol, long lastUpdateId, List<Level> bids, List<Level> asks, Instant eventTime)
            implements StreamMessage {

        public DepthMessage {
            bids = List.copyOf(bids);
            asks = List.copyOf(asks);
        }
    }

    /**
     * One price level of a {@link DepthMessage}.
     *
     * @param price the level's price
     * @param quantity the base quantity resting at it
     */
    record Level(BigDecimal price, BigDecimal quantity) {}
}
