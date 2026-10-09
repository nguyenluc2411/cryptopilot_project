package com.cryptopilot.paper.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which working orders a candle reaches: buys at or below their price, sells at or above it, never before placed. */
class PaperOrderBookTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID OTHER_PAIR = UUID.randomUUID();
    private static final Instant PLACED = Instant.parse("2026-10-07T08:00:30Z");
    private static final Instant NEXT_MINUTE = Instant.parse("2026-10-07T08:01:00Z");

    private final PaperOrderBook book = new PaperOrderBook();

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

    private static RestingOrder order(OrderSide side, String price) {
        return new RestingOrder(
                UUID.randomUUID(), UUID.randomUUID(), MarketType.SPOT, PAIR, side, new BigDecimal(price), PLACED);
    }

    private static PriceRange range(String low, String high, Instant from) {
        return new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal(low), new BigDecimal(high), from, false, FillSource.LIVE);
    }
}
