package com.cryptopilot.paper.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.LimitFillMode;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which working orders a price reaches: buys below their price, sells above it, at it too with TOUCH; never a price
 * traded before they were placed.
 */
class PaperOrderBookTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID OTHER_PAIR = UUID.randomUUID();
    private static final Instant PLACED = Instant.parse("2026-10-07T08:00:30Z");
    private static final Instant NEXT_MINUTE = Instant.parse("2026-10-07T08:01:00Z");

    private final PaperOrderBook book = new PaperOrderBook(LimitFillMode.TOUCH);

    @Test
    void TR02_aBuy_isReachedAtOrBelowItsPrice_bothBoundsCounting() {
        RestingOrder atLow = order(OrderSide.BUY, "100");
        RestingOrder inside = order(OrderSide.BUY, "105");
        RestingOrder below = order(OrderSide.BUY, "99.99");
        book.track(atLow);
        book.track(inside);
        book.track(below);

        assertThat(book.onRange(range("100", "110", NEXT_MINUTE))).containsExactlyInAnyOrder(atLow, inside);
        assertThat(book.size()).isEqualTo(1);
    }

    @Test
    void TR02_aSell_isReachedAtOrAboveItsPrice_bothBoundsCounting() {
        RestingOrder atHigh = order(OrderSide.SELL, "110");
        RestingOrder inside = order(OrderSide.SELL, "105");
        RestingOrder above = order(OrderSide.SELL, "110.01");
        book.track(atHigh);
        book.track(inside);
        book.track(above);

        assertThat(book.onRange(range("100", "110", NEXT_MINUTE))).containsExactlyInAnyOrder(atHigh, inside);
        assertThat(book.hasOrders(MarketType.SPOT, PAIR)).isTrue();
    }

    @Test
    void D77_aCandleThatOpenedBeforeTheOrderWasPlaced_neverFillsIt_butALaterOneDoes() {
        RestingOrder buy = order(OrderSide.BUY, "100");
        book.track(buy);

        assertThat(book.onRange(range("90", "110", Instant.parse("2026-10-07T08:00:00Z"))))
                .as("the candle's low may have traded before the order existed")
                .isEmpty();
        assertThat(book.onRange(range("90", "110", NEXT_MINUTE))).containsExactly(buy);
        assertThat(book.hasOrders(MarketType.SPOT, PAIR)).isFalse();
    }

    @Test
    void TR02_aCancelledOrder_isNoLongerReached_andAnUnknownOneIsIgnored() {
        RestingOrder buy = order(OrderSide.BUY, "100");
        RestingOrder kept = order(OrderSide.BUY, "100");
        book.track(buy);
        book.track(kept);

        book.untrack(buy.orderId());
        book.untrack(UUID.randomUUID());

        assertThat(book.onRange(range("90", "110", NEXT_MINUTE))).containsExactly(kept);
        assertThat(book.size()).isZero();
    }

    @Test
    void TR02_trackingAnOrderAgain_replacesIt() {
        RestingOrder buy = order(OrderSide.BUY, "100");
        book.track(buy);
        RestingOrder moved = new RestingOrder(
                buy.orderId(), buy.accountId(), MarketType.SPOT, PAIR, OrderSide.BUY, new BigDecimal("80"), PLACED);

        book.track(moved);

        assertThat(book.size()).isEqualTo(1);
        assertThat(book.onRange(range("90", "110", NEXT_MINUTE))).isEmpty();
        assertThat(book.onRange(range("80", "110", NEXT_MINUTE))).containsExactly(moved);
    }

    @Test
    void TR02_aRange_reachesOnlyTheOrdersOfItsOwnPairAndMarket() {
        RestingOrder other = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                OTHER_PAIR,
                OrderSide.BUY,
                new BigDecimal("100"),
                PLACED);
        RestingOrder futures = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.FUTURES,
                PAIR,
                OrderSide.BUY,
                new BigDecimal("100"),
                PLACED);
        book.track(other);
        book.track(futures);

        assertThat(book.onRange(range("90", "110", NEXT_MINUTE))).isEmpty();
        assertThat(book.hasOrders(MarketType.SPOT, OTHER_PAIR)).isTrue();
        assertThat(book.hasOrders(MarketType.FUTURES, PAIR)).isTrue();
        assertThat(book.hasOrders(MarketType.SPOT, PAIR)).isFalse();
    }

    @Test
    void TR02_twoOrdersAtOneLevel_areBothReached() {
        RestingOrder first = order(OrderSide.SELL, "105");
        RestingOrder second = order(OrderSide.SELL, "105");
        book.track(first);
        book.track(second);
        book.untrack(UUID.randomUUID());

        assertThat(book.onRange(range("100", "105", NEXT_MINUTE))).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void TR02_tenBuyersReached_areFilledByPriceThenByWhoPlacedFirst() {
        List<RestingOrder> buyers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // Prices 100..102 repeat; the placement time grows with i, so within a price the lower i is older.
            buyers.add(new RestingOrder(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    MarketType.SPOT,
                    PAIR,
                    OrderSide.BUY,
                    new BigDecimal(100 + i % 3),
                    PLACED.minusSeconds(100 - i)));
        }
        List<RestingOrder> shuffled = new ArrayList<>(buyers);
        Collections.shuffle(shuffled, new Random(7));
        shuffled.forEach(book::track);

        List<RestingOrder> filled = book.onRange(range("90", "110", NEXT_MINUTE));

        assertThat(filled)
                .as("highest price first, the earlier order first at one price")
                .isSortedAccordingTo(Comparator.comparing(RestingOrder::limitPrice)
                        .reversed()
                        .thenComparing(RestingOrder::placedAt))
                .containsExactlyInAnyOrderElementsOf(buyers);
    }

    @Test
    void QT8_withCross_aPriceEqualToTheOrders_doesNotReachIt_butOneThroughItDoes() {
        PaperOrderBook cross = new PaperOrderBook(LimitFillMode.CROSS);
        RestingOrder buy = order(OrderSide.BUY, "100");
        RestingOrder sell = order(OrderSide.SELL, "110");
        cross.track(buy);
        cross.track(sell);

        assertThat(cross.onRange(range("100", "110", NEXT_MINUTE)))
                .as("the queue at the order's own price may not have been reached")
                .isEmpty();
        assertThat(cross.onRange(range("99.99", "110", NEXT_MINUTE))).containsExactly(buy);
        assertThat(cross.onRange(range("100", "110.01", NEXT_MINUTE))).containsExactly(sell);
        assertThat(cross.size()).isZero();
    }

    @Test
    void D77_aTradeBeforeTheOrderWasPlaced_neverFillsIt_eachSideReadingTheTimeOfItsOwnBound() {
        RestingOrder buy = order(OrderSide.BUY, "100");
        RestingOrder sell = order(OrderSide.SELL, "110");
        book.track(buy);
        book.track(sell);
        // The low was traded before both orders were placed, the high after.
        PriceRange trades = new PriceRange(
                MarketType.SPOT,
                PAIR,
                new BigDecimal("90"),
                new BigDecimal("120"),
                PLACED.minusSeconds(1),
                false,
                FillSource.LIVE,
                PLACED.minusSeconds(1),
                PLACED.plusSeconds(1),
                new TreeMap<>(),
                new TreeMap<>());

        assertThat(book.onRange(trades)).containsExactly(sell);
        assertThat(book.size()).isEqualTo(1);
    }

    @Test
    void QT8_queue_aBuy_fillsAtItsPrice_onceTheTakersSellingThereConsumedTheQuantityAheadOfIt() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder buy = order(OrderSide.BUY, "80000");
        queue.track(buy, new BigDecimal("2"));

        assertThat(queue.onRange(trade("80000", "1.5", true))).isEmpty();
        assertThat(queue.queueAhead(buy.orderId())).contains(new BigDecimal("0.5"));
        assertThat(queue.onRange(trade("80000.00", "0.5", true)))
                .as("a trade only as large as the queue ahead does not reach the order")
                .isEmpty();
        assertThat(queue.onRange(trade("80000", "0.001", true))).containsExactly(buy);
        assertThat(queue.size()).isZero();
        assertThat(queue.queueAhead(buy.orderId())).isEmpty();
    }

    @Test
    void QT8_queue_aSell_fillsAtItsPrice_onceTheTakersBuyingThereConsumedTheQuantityAheadOfIt() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder sell = order(OrderSide.SELL, "80000");
        queue.track(sell, new BigDecimal("1"));

        assertThat(queue.onRange(trade("80000", "1", false))).isEmpty();
        assertThat(queue.onRange(trade("80000", "0.1", false))).containsExactly(sell);
    }

    @Test
    void QT8_queue_aTakerOfTheOtherSideAtTheOrdersPrice_fillsItAtOnce() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder buy = order(OrderSide.BUY, "80000");
        RestingOrder sell = order(OrderSide.SELL, "80000");
        queue.track(buy, new BigDecimal("100"));
        queue.track(sell, new BigDecimal("100"));

        assertThat(queue.onRange(trade("80000", "0.001", false)))
                .as("a taker bought at 80000: no bid was left there, so the paper BUY would have been first")
                .containsExactly(buy);
        assertThat(queue.onRange(trade("80000", "0.001", true))).containsExactly(sell);
    }

    @Test
    void QT8_queue_aPriceThroughTheOrder_fillsItWhateverIsAheadOfIt() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder buy = order(OrderSide.BUY, "80000");
        queue.track(buy, new BigDecimal("100"));

        assertThat(queue.onRange(trade("79999", "0.001", true))).containsExactly(buy);
    }

    @Test
    void QT8_queue_aQueueNotKnown_isReadFromTheDepthWhenATradeFirstReachesThePrice() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder buy = order(OrderSide.BUY, "80000");
        RestingOrder unseen = order(OrderSide.BUY, "80000");
        queue.track(buy);
        queue.track(unseen);
        PaperOrderBook.QueueLookup depth =
                (market, pairId, side, price) -> side == OrderSide.BUY && price.compareTo(new BigDecimal("80000")) == 0
                        ? Optional.of(new BigDecimal("3"))
                        : Optional.empty();

        assertThat(queue.onRange(trade("80000", "1", true), depth)).isEmpty();
        assertThat(queue.queueAhead(buy.orderId())).contains(new BigDecimal("2"));
        assertThat(queue.onRange(trade("80000", "2.5", true), (market, pairId, side, price) -> Optional.empty()))
                .containsExactlyInAnyOrder(buy, unseen);
    }

    @Test
    void QT8_queue_aCandle_carriesNoVolumeByPrice_andFillsAsCross() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder buy = order(OrderSide.BUY, "100");
        queue.track(buy, BigDecimal.ZERO);

        assertThat(queue.onRange(range("100", "110", NEXT_MINUTE))).isEmpty();
        assertThat(queue.onRange(range("99", "110", NEXT_MINUTE))).containsExactly(buy);
    }

    @Test
    void D77_queue_aTradeBeforeTheOrderWasPlaced_consumesNothingOfItsQueue() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder buy = order(OrderSide.BUY, "80000");
        queue.track(buy, new BigDecimal("1"));

        assertThat(queue.onRange(PriceRange.of(new MarketTrade(
                        MarketType.SPOT,
                        PAIR,
                        1,
                        new BigDecimal("80000"),
                        new BigDecimal("5"),
                        PLACED.minusSeconds(1),
                        true,
                        PLACED))))
                .isEmpty();
        assertThat(queue.queueAhead(buy.orderId())).contains(new BigDecimal("1"));
    }

    @Test
    void D77_mergedTrades_aTradeAfterThePlacement_fillsTheOrder_thoughAnEarlierOneSetTheLow() {
        PaperOrderBook cross = new PaperOrderBook(LimitFillMode.CROSS);
        RestingOrder buy = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.BUY,
                new BigDecimal("99"),
                at(5));
        cross.track(buy);
        PriceRange merged = tradeAt("98", "1", true, 3).withTrades(tradeAt("98.5", "1", true, 7));

        assertThat(merged.lowAt())
                .as("the low still carries its earliest trade")
                .isEqualTo(at(3));
        assertThat(cross.reach(merged, NO_QUEUE)).containsExactly(new PaperOrderBook.Reached(buy, at(7)));
        assertThat(cross.size()).isZero();
    }

    @Test
    void D77_mergedTrades_allBeforeThePlacement_neverFillTheOrder() {
        RestingOrder buy = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.BUY,
                new BigDecimal("99"),
                at(5));
        book.track(buy);

        assertThat(book.reach(tradeAt("98", "1", true, 3).withTrades(tradeAt("97", "1", true, 4)), NO_QUEUE))
                .isEmpty();
        assertThat(book.size()).isEqualTo(1);
    }

    @Test
    void D77_queue_onlyTheVolumeTradedAfterThePlacement_consumesTheQueue() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder buy = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.BUY,
                new BigDecimal("80000"),
                at(5));
        queue.track(buy, new BigDecimal("1"));
        PriceRange merged = tradeAt("80000", "5", true, 3).withTrades(tradeAt("80000", "0.4", true, 7));

        assertThat(queue.reach(merged, NO_QUEUE)).isEmpty();
        assertThat(queue.queueAhead(buy.orderId())).contains(new BigDecimal("0.6"));
        assertThat(queue.reach(tradeAt("80000", "0.7", true, 8), NO_QUEUE))
                .containsExactly(new PaperOrderBook.Reached(buy, at(8)));
    }

    @Test
    void D77_queue_mergedTrades_fillAtTheTradeThatConsumedTheQueue() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder buy = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.BUY,
                new BigDecimal("80000"),
                at(5));
        RestingOrder sell = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.SELL,
                new BigDecimal("80001"),
                at(5));
        queue.track(buy, new BigDecimal("1"));
        queue.track(sell, new BigDecimal("1"));
        PriceRange merged = tradeAt("80000", "0.6", true, 6)
                .withTrades(tradeAt("80000", "0.6", true, 7))
                .withTrades(tradeAt("80001", "0.1", true, 9));

        assertThat(queue.reach(merged, NO_QUEUE))
                .as("the sell sees a taker of the other side at its price")
                .containsExactly(new PaperOrderBook.Reached(buy, at(7)), new PaperOrderBook.Reached(sell, at(9)));
    }

    @Test
    void TR05_catchUp_fillsAnOrderJustTrackedByAnEarlierReadTrade_withoutConsumingItsQueue() {
        PaperOrderBook queue = new PaperOrderBook(LimitFillMode.QUEUE);
        RestingOrder through = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.BUY,
                new BigDecimal("99"),
                at(5));
        RestingOrder atPrice = new RestingOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MarketType.SPOT,
                PAIR,
                OrderSide.BUY,
                new BigDecimal("98"),
                at(5));
        queue.track(through, BigDecimal.ONE);
        queue.track(atPrice, BigDecimal.ONE);
        List<PriceRange.Print> prints =
                List.of(new PriceRange.Print(new BigDecimal("98"), BigDecimal.TEN, true, at(7)));

        assertThat(queue.catchUp(through.orderId(), prints)).contains(new PaperOrderBook.Reached(through, at(7)));
        assertThat(queue.catchUp(atPrice.orderId(), prints))
                .as("its queue was read from the book after that trade")
                .isEmpty();
        assertThat(queue.queueAhead(atPrice.orderId())).contains(BigDecimal.ONE);
        assertThat(queue.catchUp(UUID.randomUUID(), prints)).isEmpty();
        assertThat(queue.size()).isEqualTo(1);
    }

    private static final PaperOrderBook.QueueLookup NO_QUEUE = (market, pairId, side, price) -> Optional.empty();

    private static Instant at(int second) {
        return NEXT_MINUTE.plusSeconds(second);
    }

    private static PriceRange tradeAt(String price, String quantity, boolean buyerMaker, int second) {
        return PriceRange.of(new MarketTrade(
                MarketType.SPOT,
                PAIR,
                1,
                new BigDecimal(price),
                new BigDecimal(quantity),
                at(second),
                buyerMaker,
                at(second)));
    }

    private static PriceRange trade(String price, String quantity, boolean buyerMaker) {
        return PriceRange.of(new MarketTrade(
                MarketType.SPOT,
                PAIR,
                1,
                new BigDecimal(price),
                new BigDecimal(quantity),
                NEXT_MINUTE,
                buyerMaker,
                NEXT_MINUTE));
    }

    private static RestingOrder order(OrderSide side, String price) {
        return new RestingOrder(
                UUID.randomUUID(), UUID.randomUUID(), MarketType.SPOT, PAIR, side, new BigDecimal(price), PLACED);
    }

    private static PriceRange range(String low, String high, Instant from) {
        return new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal(low), new BigDecimal(high), from, false, FillSource.LIVE);
    }
}
