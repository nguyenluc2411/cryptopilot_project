package com.cryptopilot.paper.service;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.LiquidationLevel;
import com.cryptopilot.paper.model.ReplayedCandle;
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
 * <p>Rule: TR-02, TR-09, TR-11, TR-42; NSF-07; Q-T6.
 */
public interface PaperMatchingService {

    /** Every working order that waits at its limit price. */
    List<RestingOrder> restingOrders();

    /** Every stop limit and trailing stop that still waits for its trigger. */
    List<TriggerOrder> triggerOrders();

    /** Every Futures conditional order that waits for the mark price to trigger it (TR-20). */
    List<TriggerOrder> markTriggerOrders();

    /** The liquidation levels of every open isolated Futures position (TR-22). */
    List<LiquidationLevel> liquidationLevels();

    /** The liquidation levels of one Trader's open isolated Futures positions on one pair, as they are now (TR-22). */
    List<LiquidationLevel> liquidationLevels(UUID userId, UUID pairId);

    /**
     * Liquidates the position of the level under its Trader's lock if the mark still reaches it (TR-22).
     *
     * @return whether it was liquidated; {@code false} for a level the database no longer bears out
     */
    boolean liquidate(LiquidationLevel level, BigDecimal mark, Instant at);

    /**
     * Triggers a stop limit or a trailing stop the last price reached, under its Trader's lock, if it still waits: it
     * becomes the limit order it carries. Live, that order meets the current book at once as a limit order placed now
     * does; replayed, it waits at its price for the replayed candles that follow.
     *
     * @param at when the price that triggered it traded
     * @param stopPrice the stop it triggered at
     * @param candleOpen the first price of the replayed candle that triggered it; {@code null} when not known or live
     * @return whether this call triggered it; {@code false} when it was cancelled or triggered first, or is cancelled
     *     now as its Spot pair stopped trading (NSF-01)
     */
    boolean trigger(TriggerOrder order, Instant at, BigDecimal stopPrice, FillSource source, BigDecimal candleOpen);

    /** Stores the best prices the trailing stops of these ids followed to, for those that still wait. */
    void saveExtremes(Map<UUID, BigDecimal> extremes);

    /**
     * Fills a working order the price reached, at its own price, as a maker: under its Trader's lock, and only if it
     * still works. An order of a Spot pair the exchange stopped trading is cancelled instead (NSF-01).
     *
     * @param tradedAt when the price reached it: the market trade's time, or the open time of the candle (D-78)
     * @param candle the replayed candle that reached it, which a pending stop its fill activates is judged against;
     *     {@code null} for a live fill
     * @return whether this call filled it; {@code false} when it was cancelled or filled first, or is cancelled now
     */
    boolean fill(RestingOrder order, Instant tradedAt, FillSource source, ReplayedCandle candle);

    /** As {@link #fill(RestingOrder, Instant, FillSource, ReplayedCandle)}, without the candle that decided it. */
    boolean fill(RestingOrder order, Instant tradedAt, FillSource source);

    /**
     * Before a replay queues the pair's candles opened {@code from} to {@code to}: fetches what their fills will be
     * valued at when the pair is quoted in another coin than USDT (Q-52). Never throws.
     */
    void prefetchQuoteRates(MarketType market, UUID pairId, Instant from, Instant to);

    /** The open time of the pair's last closed and fully matched candle, from which a replay starts; or empty. */
    Optional<Instant> watermark(MarketType market, UUID pairId);

    /** Records that the candle opened at {@code openTime} is closed and fully matched; never moves backwards. */
    void advanceWatermark(MarketType market, UUID pairId, Instant openTime);

    /**
     * The open time of the Futures pair's last 1-minute mark price candle its mark triggers and liquidations ran over,
     * from which a mark replay starts; or empty (TR-42).
     */
    Optional<Instant> markWatermark(UUID pairId);

    /** Records that the mark price candle opened at {@code openTime} was run over; never moves backwards (TR-42). */
    void advanceMarkWatermark(UUID pairId, Instant openTime);

    /**
     * Whether the engine is catching up on the Futures pair's missed marks: meanwhile the pair is {@code MARK_STALE}
     * whatever its latest mark (TR-42, plan 9.5).
     */
    void markCatchingUp(UUID pairId, boolean catchingUp);
}
