package com.cryptopilot.trading.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchingRecordsTest {

    private static final UUID ID = UUID.fromString("019b76da-a800-7000-8000-0000000000a1");
    private static final Instant AT = Instant.parse("2026-10-03T08:00:00Z");
    private static final BigDecimal P = new BigDecimal("100");

    @Test
    void NSF07_aRangeOfOnePrice_isAccepted() {
        assertThat(new PriceRange(MarketType.SPOT, ID, P, new BigDecimal("100.00"), AT).low())
                .isEqualByComparingTo(P);
    }

    @Test
    void NSF07_aRangeWithItsLowAboveItsHigh_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PriceRange(MarketType.SPOT, ID, new BigDecimal("100.01"), P, AT));
    }

    @Test
    void NSF07_aRangeThatStartsAfterItEnds_isRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PriceRange(MarketType.SPOT, ID, P, P, AT.plusSeconds(1), AT));
    }

    @Test
    void NSF07_aSingleUpdate_startsAndEndsAtItsTime() {
        PriceRange range = new PriceRange(MarketType.SPOT, ID, P, P, AT);

        assertThat(range.from()).isEqualTo(AT);
        assertThat(range.at()).isEqualTo(AT);
    }

    @Test
    void NSF07_aRange_needsEveryField() {
        assertThatNullPointerException().isThrownBy(() -> new PriceRange(MarketType.SPOT, ID, P, P, null, AT));
        assertThatNullPointerException().isThrownBy(() -> new PriceRange(null, ID, P, P, AT));
        assertThatNullPointerException().isThrownBy(() -> new PriceRange(MarketType.SPOT, null, P, P, AT));
        assertThatNullPointerException().isThrownBy(() -> new PriceRange(MarketType.SPOT, ID, null, P, AT));
        assertThatNullPointerException().isThrownBy(() -> new PriceRange(MarketType.SPOT, ID, P, null, AT));
        assertThatNullPointerException().isThrownBy(() -> new PriceRange(MarketType.SPOT, ID, P, P, null));
    }

    @Test
    void NSF07_anEntry_needsEveryField() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedEntry(null, MarketType.SPOT, ID, Direction.LONG, P, AT));
        assertThatNullPointerException().isThrownBy(() -> new TrackedEntry(ID, null, ID, Direction.LONG, P, AT));
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedEntry(ID, MarketType.SPOT, null, Direction.LONG, P, AT));
        assertThatNullPointerException().isThrownBy(() -> new TrackedEntry(ID, MarketType.SPOT, ID, null, P, AT));
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedEntry(ID, MarketType.SPOT, ID, Direction.LONG, null, AT));
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedEntry(ID, MarketType.SPOT, ID, Direction.LONG, P, null));
    }

    @Test
    void A04_twoRangesMerged_keepTheExtremesTheEarliestAndLatestTime_andAreClosedWhenEitherIs() {
        PriceRange forming = new PriceRange(
                MarketType.SPOT, ID, new BigDecimal("95"), new BigDecimal("105"), AT, AT.plusSeconds(10));
        PriceRange closed = new PriceRange(
                MarketType.SPOT, ID, new BigDecimal("97"), new BigDecimal("110"), AT, AT.plusSeconds(59), true);

        PriceRange expected = new PriceRange(
                MarketType.SPOT, ID, new BigDecimal("95"), new BigDecimal("110"), AT, AT.plusSeconds(59), true);
        assertThat(forming.mergedWith(closed)).isEqualTo(expected);
        assertThat(closed.mergedWith(forming)).isEqualTo(expected);
        assertThat(forming.mergedWith(forming).closed()).isFalse();
    }

    @Test
    void BR33_aFill_needsEveryField() {
        assertThatNullPointerException().isThrownBy(() -> new Fill(null, P, AT));
        assertThatNullPointerException().isThrownBy(() -> new Fill(ID, null, AT));
        assertThatNullPointerException().isThrownBy(() -> new Fill(ID, P, null));
        assertThat(new Fill(ID, P, AT).executedAt()).isEqualTo(AT);
    }
}
