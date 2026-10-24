package com.cryptopilot.trading.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.InvalidationReason;
import com.cryptopilot.trading.model.enums.LiquidityRole;
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
    void NSF07_anEntry_needsEveryFieldButItsInvalidationPrice() {
        assertThatNullPointerException().isThrownBy(() -> entry(null, ID, EntryType.LIMIT, P, P, AT));
        assertThatNullPointerException().isThrownBy(() -> entry(ID, null, EntryType.LIMIT, P, P, AT));
        assertThatNullPointerException().isThrownBy(() -> entry(ID, ID, null, P, P, AT));
        assertThatNullPointerException().isThrownBy(() -> entry(ID, ID, EntryType.LIMIT, null, P, AT));
        assertThatNullPointerException().isThrownBy(() -> entry(ID, ID, EntryType.LIMIT, P, null, AT));
        assertThatNullPointerException().isThrownBy(() -> entry(ID, ID, EntryType.LIMIT, P, P, null));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> new TrackedEntry(ID, ID, null, ID, Direction.LONG, EntryType.LIMIT, P, P, P, null, AT));
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedEntry(
                        ID, ID, MarketType.SPOT, null, Direction.LONG, EntryType.LIMIT, P, P, P, null, AT));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> new TrackedEntry(ID, ID, MarketType.SPOT, ID, null, EntryType.LIMIT, P, P, P, null, AT));
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedEntry(
                        ID, ID, MarketType.SPOT, ID, Direction.LONG, EntryType.LIMIT, null, P, P, null, AT));
        assertThat(entry(ID, ID, EntryType.STOP, P, P, AT).invalidationPrice()).isNull();
    }

    @Test
    void BR33_aMarketEntry_isNeverTracked() {
        assertThatIllegalArgumentException().isThrownBy(() -> entry(ID, ID, EntryType.MARKET, P, P, AT));
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
    void A04_D112R4_twoRangesMerged_keepTheOpenOfTheEarlierCandle_orTheOneKnownWhenTheyShareOne() {
        PriceRange first = new PriceRange(MarketType.SPOT, ID, null, P, P, AT, AT.plusSeconds(5), false);
        PriceRange known =
                new PriceRange(MarketType.SPOT, ID, new BigDecimal("99"), P, P, AT, AT.plusSeconds(9), false);
        PriceRange next = new PriceRange(
                MarketType.SPOT, ID, new BigDecimal("101"), P, P, AT.plusSeconds(60), AT.plusSeconds(61), false);

        assertThat(first.mergedWith(known).open()).isEqualByComparingTo("99");
        assertThat(known.mergedWith(first).open()).isEqualByComparingTo("99");
        assertThat(known.mergedWith(next).open()).isEqualByComparingTo("99");
        assertThat(next.mergedWith(known).open()).isEqualByComparingTo("99");
        assertThat(first.mergedWith(next).open()).isNull();
        assertThat(new PriceRange(MarketType.SPOT, ID, P, P, AT).open()).isNull();
    }

    @Test
    void BR33_aFill_needsEveryField() {
        TrackedEntry entry = entry(ID, ID, EntryType.LIMIT, P, P, AT);
        assertThatNullPointerException().isThrownBy(() -> new Fill(null, LiquidityRole.MAKER, P, AT));
        assertThatNullPointerException().isThrownBy(() -> new Fill(entry, null, P, AT));
        assertThatNullPointerException().isThrownBy(() -> new Fill(entry, LiquidityRole.MAKER, null, AT));
        assertThatNullPointerException().isThrownBy(() -> new Fill(entry, LiquidityRole.MAKER, P, null));
        Fill fill = new Fill(entry, LiquidityRole.TAKER, P, AT);
        assertThat(fill.at()).isEqualTo(AT);
        assertThat(fill.planId()).isEqualTo(ID);
    }

    @Test
    void BR68_anInvalidation_needsEveryField() {
        TrackedEntry entry = entry(ID, ID, EntryType.STOP, P, P, AT);
        assertThatNullPointerException()
                .isThrownBy(() -> new Invalidation(null, InvalidationReason.STOP_LOSS_REACHED, AT));
        assertThatNullPointerException().isThrownBy(() -> new Invalidation(entry, null, AT));
        assertThatNullPointerException()
                .isThrownBy(() -> new Invalidation(entry, InvalidationReason.STOP_LOSS_REACHED, null));
        Invalidation invalidation = new Invalidation(entry, InvalidationReason.STOP_LOSS_REACHED, AT);
        assertThat(invalidation.at()).isEqualTo(AT);
        assertThat(invalidation.planId()).isEqualTo(ID);
    }

    private static TrackedEntry entry(
            UUID planId, UUID userId, EntryType entryType, BigDecimal stopLoss, BigDecimal takeProfit, Instant at) {
        return new TrackedEntry(
                planId, userId, MarketType.SPOT, ID, Direction.LONG, entryType, P, stopLoss, takeProfit, null, at);
    }
}
