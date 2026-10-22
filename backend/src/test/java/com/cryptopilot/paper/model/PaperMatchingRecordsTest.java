package com.cryptopilot.paper.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The values the paper matching engine passes around: complete, and merged the way the stream needs. */
class PaperMatchingRecordsTest {

    private static final UUID PAIR = UUID.randomUUID();
    private static final Instant MINUTE = Instant.parse("2026-10-07T08:00:00Z");

    @Test
    void TR02_twoUpdatesOfOneCandle_mergeIntoItsLowestLowAndHighestHigh_closedWhenEitherIs() {
        PriceRange merged = range("100", "105", MINUTE, false).mergedWith(range("98", "103", MINUTE, true));

        assertThat(merged.low()).isEqualByComparingTo("98");
        assertThat(merged.high()).isEqualByComparingTo("105");
        assertThat(merged.closed()).isTrue();
        assertThat(merged.from()).isEqualTo(MINUTE);
    }

    @Test
    void TR02_anUpdateOfALaterCandle_replacesTheEarlierOne_andAnEarlierOneIsIgnored() {
        PriceRange earlier = range("100", "105", MINUTE, false);
        PriceRange later = range("90", "91", MINUTE.plusSeconds(60), false);

        assertThat(earlier.mergedWith(later)).isSameAs(later);
        assertThat(later.mergedWith(earlier)).isSameAs(later);
    }

    @Test
    void TR05_aTrade_isARangeAtItsPrice_andTradesMerge_keepingTheTimeOfTheirLowAndOfTheirHigh() {
        PriceRange first = PriceRange.of(trade("100", MINUTE));
        PriceRange merged = first.withTrades(PriceRange.of(trade("98", MINUTE.plusMillis(10))))
                .withTrades(PriceRange.of(trade("103", MINUTE.plusMillis(20))))
                .withTrades(PriceRange.of(trade("98", MINUTE.plusMillis(30))));

        assertThat(first.low()).isEqualByComparingTo("100");
        assertThat(first.high()).isEqualByComparingTo("100");
        assertThat(first.lowAt()).isEqualTo(MINUTE);
        assertThat(first.closed()).isFalse();
        assertThat(merged.low()).isEqualByComparingTo("98");
        assertThat(merged.lowAt()).as("the earliest trade at the low").isEqualTo(MINUTE.plusMillis(10));
        assertThat(merged.high()).isEqualByComparingTo("103");
        assertThat(merged.highAt()).isEqualTo(MINUTE.plusMillis(20));
        assertThat(merged.from()).isEqualTo(MINUTE);
        assertThat(merged.source()).isEqualTo(FillSource.LIVE);
    }

    @Test
    void TR05_tradesKeepTheVolumeTakersSoldAndBoughtAtEachPrice_addedUpByValue() {
        PriceRange sold = PriceRange.of(new MarketTrade(
                MarketType.SPOT, PAIR, 1, new BigDecimal("80000"), new BigDecimal("1.5"), MINUTE, true, MINUTE));
        PriceRange bought = PriceRange.of(new MarketTrade(
                MarketType.SPOT, PAIR, 2, new BigDecimal("80001"), new BigDecimal("2"), MINUTE, false, MINUTE));
        PriceRange soldAgain = PriceRange.of(new MarketTrade(
                MarketType.SPOT, PAIR, 3, new BigDecimal("80000.00"), new BigDecimal("0.5"), MINUTE, true, MINUTE));

        PriceRange merged = sold.withTrades(bought).withTrades(soldAgain);

        assertThat(sold.boughtAt()).isEmpty();
        assertThat(merged.soldAt()).hasSize(1);
        assertThat(merged.soldAt().get(new BigDecimal("80000"))).isEqualByComparingTo("2");
        assertThat(merged.boughtAt().get(new BigDecimal("80001"))).isEqualByComparingTo("2");
        assertThat(range("1", "2", MINUTE, true).soldAt()).isEmpty();
    }

    @Test
    void D77_mergedTrades_keepEachTradeInTheOrderItTraded_evenWhenReadOutOfOrder() {
        PriceRange early = PriceRange.of(trade("100", MINUTE.plusMillis(10)))
                .withTrades(PriceRange.of(trade("101", MINUTE.plusMillis(30))));
        PriceRange late = PriceRange.of(trade("99", MINUTE.plusMillis(20)));

        PriceRange merged = early.withTrades(late);

        assertThat(merged.prints())
                .extracting(PriceRange.Print::at)
                .containsExactly(MINUTE.plusMillis(10), MINUTE.plusMillis(20), MINUTE.plusMillis(30));
        assertThat(merged.lowAt()).isEqualTo(MINUTE.plusMillis(20));
        assertThat(range("1", "2", MINUTE, true).prints()).isEmpty();
        assertThat(merged.withTrades(range("1", "200", MINUTE, false)).prints())
                .as("a range without trades adds none")
                .hasSize(3);
    }

    @Test
    void TR05_aRangeOfPrints_takesItsLowHighAndVolumes_fromThem() {
        PriceRange range = PriceRange.ofPrints(
                MarketType.SPOT,
                PAIR,
                List.of(
                        new PriceRange.Print(new BigDecimal("100"), BigDecimal.ONE, true, MINUTE),
                        new PriceRange.Print(new BigDecimal("98"), BigDecimal.ONE, false, MINUTE.plusMillis(1)),
                        new PriceRange.Print(new BigDecimal("98"), BigDecimal.TEN, true, MINUTE.plusMillis(2)),
                        new PriceRange.Print(new BigDecimal("102"), BigDecimal.ONE, false, MINUTE.plusMillis(3))));

        assertThat(range.low()).isEqualByComparingTo("98");
        assertThat(range.lowAt()).isEqualTo(MINUTE.plusMillis(1));
        assertThat(range.high()).isEqualByComparingTo("102");
        assertThat(range.from()).isEqualTo(MINUTE);
        assertThat(range.soldAt().get(new BigDecimal("98"))).isEqualByComparingTo("10");
        assertThat(range.boughtAt().get(new BigDecimal("98"))).isEqualByComparingTo("1");
        assertThatIllegalArgumentException().isThrownBy(() -> PriceRange.ofPrints(MarketType.SPOT, PAIR, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new PriceRange.Print(null, BigDecimal.ONE, true, MINUTE));
    }

    @Test
    void TR05_aCandle_recordsItsLowAndHighAtItsOpenTime() {
        PriceRange candle = range("100", "105", MINUTE, true);

        assertThat(candle.lowAt()).isEqualTo(MINUTE);
        assertThat(candle.highAt()).isEqualTo(MINUTE);
    }

    @Test
    void TR02_aRangeWhoseLowIsAboveItsHigh_isRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> range("110", "100", MINUTE, false));
        assertThatNullPointerException()
                .isThrownBy(() ->
                        new PriceRange(MarketType.SPOT, PAIR, BigDecimal.ONE, BigDecimal.ONE, MINUTE, false, null));
    }

    @Test
    void TR02_aRestingOrder_namesEveryPart() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RestingOrder(
                        UUID.randomUUID(), UUID.randomUUID(), MarketType.SPOT, PAIR, null, BigDecimal.ONE, MINUTE));
        assertThatNullPointerException().isThrownBy(() -> new PaperOrderRested(null));
        assertThatNullPointerException().isThrownBy(() -> new PlacedOrder(null, true));
    }

    @Test
    void TR02_aPlacedOrder_carriesWhetherThisRequestPlacedIt() {
        OrderResponse order = new OrderResponse(
                UUID.randomUUID(),
                "k",
                PAIR,
                "BTCUSDT",
                MarketType.SPOT,
                null,
                null,
                null,
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                OrderStatus.NEW,
                null,
                MINUTE,
                null,
                null,
                null,
                null,
                null);

        assertThat(new PlacedOrder(order, false).created()).isFalse();
        assertThat(OrderStatus.NEW.isOpen()).isTrue();
        assertThat(OrderStatus.FILLED.isOpen()).isFalse();
    }

    private static MarketTrade trade(String price, Instant at) {
        return new MarketTrade(MarketType.SPOT, PAIR, 1, new BigDecimal(price), BigDecimal.ONE, at, false, at);
    }

    private static PriceRange range(String low, String high, Instant from, boolean closed) {
        return new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal(low), new BigDecimal(high), from, closed, FillSource.LIVE);
    }
}
