package com.cryptopilot.paper.service;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The storage side of the paper matching engine: the orders its book starts from, the fills it decides, and how far
 * it has matched.
 *
 * <p>Rule: TR-02, TR-09, TR-11; NSF-07; Q-T6.
 */
public interface PaperMatchingService {

    /** Every working order that waits at its limit price. */
    List<RestingOrder> restingOrders();

    /** Every stop limit and trailing stop that still waits for its trigger. */
    List<TriggerOrder> triggerOrders();

    /**
     * Triggers a stop limit or a trailing stop the last price reached, under its Trader's lock, if it still waits: it
     * becomes the limit order it carries. Live, that order meets the current book at once as a limit order placed now
     * does; replayed, it waits at its price for the replayed candles that follow.
     *
     * @param at when the price that triggered it traded
     * @param stopPrice the stop it triggered at
     * @param candleOpen the first price of the replayed candle that triggered it; {@code null} when not known or live
     * @return whether this call triggered it; {@code false} when it was cancelled or triggered first
     */
    boolean trigger(TriggerOrder order, Instant at, BigDecimal stopPrice, FillSource source, BigDecimal candleOpen);

    /** Stores the best prices the trailing stops of these ids followed to, for those that still wait. */
    void saveExtremes(Map<UUID, BigDecimal> extremes);

    /**
     * Fills a working order the price reached, at its own price, as a maker: under its Trader's lock, and only if it
     * still works.
     *
     * @param tradedAt when the price reached it: the market trade's time, or the open time of the candle (D-78)
     * @return whether this call filled it; {@code false} when it was cancelled or filled first
     */
    boolean fill(RestingOrder order, Instant tradedAt, FillSource source);

    /** The open time of the pair's last closed and fully matched candle, from which a replay starts; or empty. */
    Optional<Instant> watermark(MarketType market, UUID pairId);

    /** Records that the candle opened at {@code openTime} is closed and fully matched; never moves backwards. */
    void advanceWatermark(MarketType market, UUID pairId, Instant openTime);
}
