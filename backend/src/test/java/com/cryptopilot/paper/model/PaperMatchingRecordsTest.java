package com.cryptopilot.paper.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.dto.response.OrderResponse;
import com.cryptopilot.paper.event.PaperOrderRested;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
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
                null);

        assertThat(new PlacedOrder(order, false).created()).isFalse();
        assertThat(OrderStatus.NEW.isOpen()).isTrue();
        assertThat(OrderStatus.FILLED.isOpen()).isFalse();
    }

    private static PriceRange range(String low, String high, Instant from, boolean closed) {
        return new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal(low), new BigDecimal(high), from, closed, FillSource.LIVE);
    }
}
