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
                .isThrownBy(() -> new TrackedEntry(null, MarketType.SPOT, ID, Direction.LONG, P));
        assertThatNullPointerException().isThrownBy(() -> new TrackedEntry(ID, null, ID, Direction.LONG, P));
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedEntry(ID, MarketType.SPOT, null, Direction.LONG, P));
        assertThatNullPointerException().isThrownBy(() -> new TrackedEntry(ID, MarketType.SPOT, ID, null, P));
        assertThatNullPointerException()
                .isThrownBy(() -> new TrackedEntry(ID, MarketType.SPOT, ID, Direction.LONG, null));
    }
}
