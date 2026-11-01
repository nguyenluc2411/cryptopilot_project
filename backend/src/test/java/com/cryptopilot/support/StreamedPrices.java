package com.cryptopilot.support;

import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Writes into the price cache what the exchange's streams would: the last price of a pair and its Futures mark price,
 * each at the exchange's instant given, and flushes them at once. A later instant replaces an earlier one; an earlier
 * one is ignored, as a late stream message is.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.6; D-111 E2/E3.
 */
public final class StreamedPrices {

    private final PriceCacheService prices;

    public StreamedPrices(PriceCacheService prices) {
        this.prices = prices;
    }

    /**
     * The last price of {@code symbol} on {@code market} at {@code at}: a ticker on Spot, a forming 1-minute candle
     * update on Futures, whose close is the last price.
     */
    public void last(MarketType market, String symbol, BigDecimal value, Instant at) {
        if (market == MarketType.FUTURES) {
            Instant open = at.truncatedTo(ChronoUnit.MINUTES);
            prices.record(
                    market,
                    new KlineMessage(
                            symbol,
                            MarketInterval.ONE_MINUTE,
                            new Kline(
                                    open,
                                    open.plusSeconds(60).minusMillis(1),
                                    value,
                                    value,
                                    value,
                                    value,
                                    BigDecimal.ONE,
                                    BigDecimal.ONE,
                                    1),
                            false,
                            at));
        } else {
            prices.record(
                    market,
                    new TickerMessage(
                            symbol,
                            value,
                            value,
                            value,
                            value,
                            value,
                            BigDecimal.ZERO,
                            BigDecimal.ONE,
                            BigDecimal.ONE,
                            at));
        }
        prices.flush();
    }

    /** The Futures mark price of {@code symbol} at {@code at}, with no funding rate and the next funding an hour on. */
    public void mark(String symbol, BigDecimal value, Instant at) {
        prices.record(
                MarketType.FUTURES,
                new MarkPriceMessage(symbol, value, value, BigDecimal.ZERO, at.plus(Duration.ofHours(1)), at));
        prices.flush();
    }
}
