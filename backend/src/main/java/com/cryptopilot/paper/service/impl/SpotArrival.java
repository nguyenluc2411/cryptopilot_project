package com.cryptopilot.paper.service.impl;

import com.cryptopilot.market.BookTop;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.PaperAccount;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.matching.BookSweep;
import com.cryptopilot.paper.matching.BookSweep.Slice;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.TimeInForce;
import com.cryptopilot.paper.model.enums.WalletType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a Spot limit order does when it reaches the market: when it is placed (TR-06), and when a stop limit or a
 * trailing stop is triggered and becomes the limit order it carries (TR-09, TR-11). It takes the levels of the other
 * side of the exchange's book at or better than its price, as a taker; what is left waits in the book (GTC) or
 * expires (IOC). A FOK order the levels cannot fill whole executes nothing and expires, and a post-only (GTX) order
 * that would take a level expires.
 *
 * <p>The other side of the book is the current depth20, or without one the best bid and ask as a single level, or for
 * a pair without the trading streams the current last price, at which the whole order executes (Q-T7). None is ever
 * executed at a price that is not current (NSF-03).
 *
 * <p>Runs inside the caller's transaction, which holds the Trader's paper lock and has locked what the order needs.
 *
 * <p>Rule: TR-06, TR-09, TR-11; NSF-03; Q-T7.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SpotArrival {

    /** Why an IOC or FOK limit expired: the price was not there on arrival. */
    static final String NOT_FILLABLE = "NOT_FILLABLE_ON_ARRIVAL";

    /** Why a post-only (GTX) limit expired: it would have executed on arrival. */
    static final String WOULD_TAKE = "POST_ONLY_WOULD_TAKE";

    private final PaperWallets wallets;
    private final SpotSettlement settlement;
    private final MarketApi market;
    private final ApplicationEventPublisher events;

    /**
     * The other side of the book an order of {@code side} takes: the asks for a BUY, the bids for a SELL. Empty
     * without any current price.
     */
    public Optional<Book> bookFor(TradablePair pair, OrderSide side) {
        boolean buy = side == OrderSide.BUY;
        Optional<List<PriceLevel>> depth = market.currentDepth(MarketType.SPOT, pair.pairId())
                .map(found -> buy ? found.asks() : found.bids())
                .filter(levels -> !levels.isEmpty());
        if (depth.isPresent()) {
            return Optional.of(new Book(depth.get(), true));
        }
        Optional<PriceLevel> top = market.currentBookTop(MarketType.SPOT, pair.pairId())
                .map(found -> buy ? bestAsk(found) : bestBid(found))
                .filter(level -> level.price().signum() > 0);
        if (top.isPresent()) {
            return Optional.of(new Book(List.of(top.get()), true));
        }
        return market.currentLastPrice(MarketType.SPOT, pair.symbol())
                .map(last -> new Book(List.of(new PriceLevel(last, BigDecimal.ZERO)), false));
    }

    /**
     * A limit order that has locked what it needs meets the book: see the class comment. Without a current price a GTC
     * or GTX order waits, and an IOC or FOK one, which must be decided now, expires.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void limitOnArrival(
            PaperAccount account, PaperOrder order, PairCoins coins, Optional<Book> book, Instant now) {
        TimeInForce timeInForce = order.getTimeInForce();
        boolean decidedNow = timeInForce == TimeInForce.IOC || timeInForce == TimeInForce.FOK;
        CoinListing locked = lockedCoin(order, coins);
        BookSweep.Result sweep = book.map(found -> BookSweep.ofQuantity(
                        found.levels(), order.getSide(), order.remainingQuantity(), order.getLimitPrice()))
                .orElseGet(() -> new BookSweep.Result(List.of(), false));
        boolean wouldTake = timeInForce == TimeInForce.GTX && !sweep.isEmpty();
        boolean notWhole = timeInForce == TimeInForce.FOK && sweep.quantity().compareTo(order.remainingQuantity()) < 0;
        if (wouldTake || notWhole) {
            wallets.unlock(account, WalletType.SPOT, locked, order.lockedAmount());
            order.expire(wouldTake ? WOULD_TAKE : NOT_FILLABLE, now);
            return;
        }
        execute(account, order, coins, sweep, now, true);
        if (!order.isOpen()) {
            if (sweep.pastBook() && book.get().shown()) {
                order.flagDepthExhausted();
            }
            return;
        }
        if (decidedNow) {
            wallets.unlock(account, WalletType.SPOT, locked, order.lockedAmount());
            order.expire(NOT_FILLABLE, now);
            return;
        }
        rest(account, order);
    }

    /** Settles each slice of a sweep as one fill, as a taker. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(
            PaperAccount account,
            PaperOrder order,
            PairCoins coins,
            BookSweep.Result sweep,
            Instant at,
            boolean fromLocked) {
        for (Slice slice : sweep.slices()) {
            settlement.execute(
                    account,
                    order,
                    coins,
                    slice.price(),
                    slice.quantity(),
                    Liquidity.TAKER,
                    FillSource.LIVE,
                    at,
                    fromLocked);
        }
    }

    /** Hands a working limit order to the matching engine, which tracks it once the transaction commits. */
    public void rest(PaperAccount account, PaperOrder order) {
        events.publishEvent(new PaperOrderRested(new RestingOrder(
                order.getId(),
                account.getId(),
                order.getMarketType(),
                order.getPairId(),
                order.getSide(),
                order.getLimitPrice(),
                order.restingSince())));
    }

    /** What a working order locks: the quote coin for a buy, the base coin for a sell. */
    static CoinListing lockedCoin(PaperOrder order, PairCoins coins) {
        return order.getSide() == OrderSide.BUY ? coins.quote() : coins.base();
    }

    private static PriceLevel bestAsk(BookTop top) {
        return new PriceLevel(top.askPrice(), top.askQuantity());
    }

    private static PriceLevel bestBid(BookTop top) {
        return new PriceLevel(top.bidPrice(), top.bidQuantity());
    }

    /**
     * The other side of the book an order takes on arrival.
     *
     * @param levels best first
     * @param shown whether the levels are the exchange's book, so taking more than they hold is flagged; {@code false}
     *     for the last price, whose depth is not known
     */
    public record Book(List<PriceLevel> levels, boolean shown) {}
}
