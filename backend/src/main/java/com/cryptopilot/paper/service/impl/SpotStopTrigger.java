package com.cryptopilot.paper.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a Spot stop does once the market reached its stop, whether the matching engine saw the price reach it or a
 * pending stop of a group was already met when its working order filled: it records the trigger and works as the
 * order it carries. A STOP_LOSS or TAKE_PROFIT executes as a MARKET order; a STOP_LOSS_LIMIT or TAKE_PROFIT_LIMIT meets
 * the book as a LIMIT order. Live, they meet the current book; replayed, the book of then is not known, so a market
 * stop executes at its stop, or at the open of a candle that gapped past it, and a limit one waits at its price for
 * the candles that follow.
 *
 * <p>Runs inside the caller's transaction, which holds the Trader's paper lock; the caller has checked that the order
 * still waits for its trigger, and expired the other order of its OCO.
 *
 * <p>Rule: TR-09, TR-10, TR-11; PT-R14; Q-51.
 *
 * <p>Reference: Harris, L. (2003). <i>Trading and Exchanges: Market Microstructure for Practitioners</i>. Oxford
 * University Press, ch. 4 (a triggered stop becomes a market order and executes at the next available price).
 * <p>Reference: Binance Spot API documentation, "Order lists (OTO/OTOCO)" and "Trailing Stop FAQ".
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SpotStopTrigger {

    private static final Logger log = LoggerFactory.getLogger(SpotStopTrigger.class);

    private final SpotArrival arrival;
    private final PaperUserFeed feed;
    private final MarketApi market;
    private final Clock clock;

    /**
     * Triggers the stop at {@code at} and executes what it carries: see the class comment.
     *
     * @param stopPrice the stop it triggered at: its stopPrice, or a trailing stop's at that moment
     * @param candleOpen the first price of the replayed candle that triggered it; {@code null} when not known or live
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(
            PaperAccount account,
            PaperOrder order,
            PairCoins coins,
            Instant at,
            BigDecimal stopPrice,
            FillSource source,
            BigDecimal candleOpen) {
        order.trigger(at);
        feed.order(account, order);
        log.info(
                "TR-09 paper order {} {} triggered at {} ({}, {})",
                order.getId(),
                order.getOrderType(),
                stopPrice,
                at,
                source);
        boolean replayed = source == FillSource.REPLAY;
        if (order.getOrderType().isSpotMarketStop()) {
            Optional<SpotArrival.Book> book = replayed
                    ? Optional.of(new SpotArrival.Book(
                            List.of(new PriceLevel(replayedStopPrice(order, stopPrice, candleOpen), BigDecimal.ZERO)),
                            false))
                    : arrival.bookFor(pairOf(order), order.getSide());
            arrival.marketOnTrigger(account, order, coins, book, replayed ? at : clock.instant(), source);
        } else if (replayed) {
            // The candles replayed after this one fill it at its price; the book of now is not the book of then.
            arrival.rest(account, order);
        } else {
            arrival.limitOnArrival(
                    account, order, coins, arrival.bookFor(pairOf(order), order.getSide()), clock.instant());
        }
    }

    /**
     * The price a replayed STOP_LOSS or TAKE_PROFIT executes at: the open of the candle that triggered it when its
     * trigger condition already held at that open, as the market had gapped through the stop; otherwise the stop
     * (Q-51). A trailing stop triggers on the pullback, the way of a STOP_LOSS of its side.
     *
     * <p>Rule: TR-09; PT-R14.
     */
    static BigDecimal replayedStopPrice(PaperOrder order, BigDecimal stopPrice, BigDecimal candleOpen) {
        if (candleOpen == null) {
            return stopPrice;
        }
        TriggerCondition condition = order.getCallbackRate() != null
                ? OrderType.STOP_LOSS.triggerConditionFor(order.getSide())
                : order.getTriggerCondition();
        return condition.isMet(candleOpen, stopPrice) ? candleOpen : stopPrice;
    }

    private TradablePair pairOf(PaperOrder order) {
        return market.tradablePair(order.getPairId(), order.getMarketType())
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not tradable"));
    }
}
