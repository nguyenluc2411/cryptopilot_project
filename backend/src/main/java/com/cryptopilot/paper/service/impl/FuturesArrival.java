package com.cryptopilot.paper.service.impl;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.matching.BookSweep;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.TimeInForce;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a stored Futures MARKET or LIMIT order does when it reaches the market: when it is placed (TR-15), and when a
 * conditional order places it on its trigger (TR-20). It takes the levels of the other side of the exchange's Futures
 * book, as a taker, each level one execution applied to the position by {@link FuturesSettlement}; what is left of a
 * LIMIT order waits in the book (GTC) or expires (IOC); a FOK order the levels cannot fill whole executes nothing and
 * expires; a post-only (GTX) order that would take a level expires. Placement refuses those cases before it stores the
 * order, as the exchange refuses them; an order placed by a trigger cannot be refused, so it ends.
 *
 * <p>The other side of the book is the current depth20, or without one the best bid and ask as a single level, or the
 * last price, whose depth is not known. On a pair whose mark is stale it is only a depth or a best bid and ask the
 * stream showed within {@code mark-max-age}, never the last price (8.2.1 rule 2). A MARKET order, or an IOC or FOK one,
 * without any current price expires.
 *
 * <p>Runs inside the caller's transaction, which holds the Trader's lock.
 *
 * <p>Rule: TR-15, TR-20; Q-T7.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesArrival {

    /** Why an order expired: the price was not there on arrival. */
    static final String NOT_FILLABLE = "NOT_FILLABLE_ON_ARRIVAL";

    private final FuturesSettlement settlement;
    private final SpotArrival arrival;
    private final MarketApi market;
    private final PaperFuturesProperties futures;
    private final Clock clock;

    /**
     * The other side of the pair's Futures book an order of {@code side} takes; empty without any current price. On a
     * pair whose mark is stale it is only what the stream showed within {@code mark-max-age}, never the last price:
     * whatever fills there, a close, a triggered order or a liquidation, may not fill on a book as old as the mark
     * (8.2.1 rule 2).
     *
     * @param markFresh whether the pair's mark is current, as the caller read it once
     */
    Optional<SpotArrival.Book> bookFor(UUID pairId, String symbol, OrderSide side, boolean markFresh) {
        Instant oldest = markFresh ? Instant.MIN : clock.instant().minus(futures.markMaxAge());
        boolean buy = side == OrderSide.BUY;
        Optional<List<PriceLevel>> depth = market.currentDepth(MarketType.FUTURES, pairId)
                .filter(found -> !found.receivedAt().isBefore(oldest))
                .map(found -> buy ? found.asks() : found.bids())
                .filter(levels -> !levels.isEmpty());
        if (depth.isPresent()) {
            return Optional.of(new SpotArrival.Book(depth.get(), true));
        }
        Optional<PriceLevel> best = market.currentBookTop(MarketType.FUTURES, pairId)
                .filter(found -> !found.receivedAt().isBefore(oldest))
                .map(found -> buy
                        ? new PriceLevel(found.askPrice(), found.askQuantity())
                        : new PriceLevel(found.bidPrice(), found.bidQuantity()))
                .filter(level -> level.price().signum() > 0);
        if (best.isPresent()) {
            return Optional.of(new SpotArrival.Book(List.of(best.get()), true));
        }
        if (!markFresh) {
            return Optional.empty();
        }
        return market.currentLastPrice(MarketType.FUTURES, symbol)
                .map(last -> new SpotArrival.Book(List.of(new PriceLevel(last, BigDecimal.ZERO)), false));
    }

    /**
     * Whether an order of {@code side} at {@code price} would take on arrival on a pair whose mark is stale, where 8.2.1
     * rule 1 refuses what opens risk and takes (TR-22a): it would when any price the stream still shows reaches it, the
     * depth, the best bid and ask or the last trade, however old within the market data's own limit, or the book the
     * order is about to meet, read by the caller. Deliberately cautious and the same for every time in force, post-only
     * included: on a stale pair the book an order meets may be emptier than what the stream shows, so an order let
     * through as one that waits could otherwise rest across a shown price and later fill at its own price through the
     * market. A MARKET order always takes.
     *
     * @param price the limit price, or {@code null} for a MARKET order
     * @param book the book the order will meet, as the caller read it
     */
    boolean anyPriceReaches(
            UUID pairId, String symbol, OrderSide side, BigDecimal price, Optional<SpotArrival.Book> book) {
        if (price == null) {
            return true;
        }
        boolean buy = side == OrderSide.BUY;
        Optional<BigDecimal> met = book.map(found -> found.levels().getFirst().price());
        Optional<BigDecimal> depth = market.currentDepth(MarketType.FUTURES, pairId)
                .map(found -> buy ? found.asks() : found.bids())
                .filter(levels -> !levels.isEmpty())
                .map(levels -> levels.getFirst().price());
        Optional<BigDecimal> top = market.currentBookTop(MarketType.FUTURES, pairId)
                .map(found -> buy ? found.askPrice() : found.bidPrice())
                .filter(best -> best.signum() > 0);
        Optional<BigDecimal> last = market.currentLastPrice(MarketType.FUTURES, symbol);
        return Stream.of(met, depth, top, last)
                .flatMap(Optional::stream)
                .anyMatch(known -> BookSweep.reaches(side, known, price));
    }

    /** What an order of {@code quantity} would take of the book at or better than {@code limit}; nothing without one. */
    static BookSweep.Result sweep(
            Optional<SpotArrival.Book> book, OrderSide side, BigDecimal quantity, BigDecimal limit) {
        return book.map(found -> BookSweep.ofQuantity(found.levels(), side, quantity, limit))
                .orElseGet(() -> new BookSweep.Result(List.of(), false));
    }

    /** A stored MARKET order takes the book at once; what the position did not take, or no price, ends it. */
    @Transactional(propagation = Propagation.MANDATORY)
    void market(PaperAccount account, PaperOrder order, Optional<SpotArrival.Book> book, Instant now) {
        BookSweep.Result sweep = sweep(book, order.getSide(), order.remainingQuantity(), null);
        execute(account, order, sweep, now, Optional.empty());
        if (order.isOpen()) {
            order.expire(NOT_FILLABLE, now);
        }
        flagPastBook(order, sweep, book);
    }

    /** A stored LIMIT order meets the book as its time in force says; see the class comment. */
    @Transactional(propagation = Propagation.MANDATORY)
    void limit(PaperAccount account, PaperOrder order, Optional<SpotArrival.Book> book, Instant now) {
        TimeInForce timeInForce = order.getTimeInForce();
        BookSweep.Result sweep = sweep(book, order.getSide(), order.remainingQuantity(), order.getLimitPrice());
        boolean decidedNow = timeInForce.immediate();
        if (timeInForce == TimeInForce.GTX && !sweep.isEmpty()) {
            order.expire(SpotArrival.WOULD_TAKE, now);
            return;
        }
        if ((decidedNow && book.isEmpty())
                || (timeInForce == TimeInForce.FOK && sweep.quantity().compareTo(order.remainingQuantity()) < 0)) {
            order.expire(NOT_FILLABLE, now);
            return;
        }
        // A FOK order is checked whole before it sweeps: nothing fills rather than a part (4.3 rule 6).
        Optional<FuturesSettlement.WholeCheck> whole = timeInForce == TimeInForce.FOK
                ? Optional.of(settlement.checkWhole(account, order, sweep.slices()))
                : Optional.empty();
        if (whole.isPresent() && whole.get().refusal().isPresent()) {
            order.expire(whole.get().refusal().get(), now);
            return;
        }
        execute(account, order, sweep, now, whole);
        if (!order.isOpen()) {
            flagPastBook(order, sweep, book);
            return;
        }
        if (decidedNow) {
            order.expire(NOT_FILLABLE, now);
            return;
        }
        arrival.rest(account, order);
    }

    /**
     * Applies each level of a sweep as a live execution, as a taker, until the order ends.
     *
     * @param whole the check that found the whole sweep fundable before it began, as a FOK order's is; empty otherwise
     */
    private void execute(
            PaperAccount account,
            PaperOrder order,
            BookSweep.Result sweep,
            Instant at,
            Optional<FuturesSettlement.WholeCheck> whole) {
        for (BookSweep.Slice slice : sweep.slices()) {
            if (!order.isOpen()) {
                return;
            }
            if (whole.isPresent()) {
                settlement.executeWhole(account, order, slice.price(), slice.quantity(), at, whole.get());
            } else {
                settlement.execute(
                        account, order, slice.price(), slice.quantity(), Liquidity.TAKER, FillSource.LIVE, at);
            }
        }
    }

    private static void flagPastBook(PaperOrder order, BookSweep.Result sweep, Optional<SpotArrival.Book> book) {
        if (order.getOrderStatus() == OrderStatus.FILLED
                && sweep.pastBook()
                && book.map(SpotArrival.Book::shown).orElse(false)) {
            order.flagDepthExhausted();
        }
    }
}
