package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * When a futures pair has a settlement to read: from the next funding time and the interval the exchange states,
 * never from an assumed one (BR-11).
 *
 * <p>Rule: BR-11; NSF-04.
 */
class FundingSettlementDueTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:06:30Z");

    /**
     * The intervals the exchange uses today, each with the settlement just passed not yet stored (due) and
     * already stored a few milliseconds late, as the exchange stamps it (not due).
     */
    @ParameterizedTest(name = "{0}h: next {1}, stored {2} -> due {3}")
    @CsvSource({
        "8, 2026-09-24T16:00:00Z, 2026-09-24T00:00:00.005Z, true",
        "8, 2026-09-24T16:00:00Z, 2026-09-24T08:00:00.005Z, false",
        "4, 2026-09-24T12:00:00Z, 2026-09-24T04:00:00Z, true",
        "4, 2026-09-24T12:00:00Z, 2026-09-24T08:00:00.001Z, false",
        "1, 2026-09-24T11:00:00Z, 2026-09-24T09:00:00Z, true",
        "1, 2026-09-24T11:00:00Z, 2026-09-24T10:00:00Z, false"
    })
    void BR11_eachStatedInterval_findsTheSettlementJustPassed(int hours, Instant next, Instant stored, boolean due) {
        assertThat(FundingSettlementDue.isDue(stored, next, Optional.of(Duration.ofHours(hours)), NOW))
                .isEqualTo(due);
    }

    /**
     * A symbol the exchange states no interval for is checked every time — including the case an 8-hour
     * assumption would call not due.
     */
    @Test
    void BR11_noStatedInterval_isAlwaysDue_neverAssumedToBeEightHours() {
        Instant next = Instant.parse("2026-09-24T16:00:00Z");
        Instant stored = Instant.parse("2026-09-24T08:00:00Z");

        assertThat(FundingSettlementDue.isDue(stored, next, Optional.empty(), NOW))
                .isTrue();
        assertThat(FundingSettlementDue.isDue(stored, next, Optional.of(Duration.ofHours(8)), NOW))
                .as("what an assumed 8 h would have answered")
                .isFalse();
    }

    /** Nothing stored: the history is read once, whatever the schedule. */
    @Test
    void NSF04_aPairWithNothingStored_isDue() {
        assertThat(FundingSettlementDue.isDue(
                        null, Instant.parse("2026-09-24T16:00:00Z"), Optional.of(Duration.ofHours(8)), NOW))
                .isTrue();
    }

    /** At the settlement instant itself it is due; a millisecond before, the previous one is still the latest. */
    @Test
    void BR11_theBoundary_isTheSettlementInstant() {
        Instant stored = Instant.parse("2026-09-24T09:00:00Z");
        Instant next = Instant.parse("2026-09-24T11:00:00Z");
        Optional<Duration> hourly = Optional.of(Duration.ofHours(1));

        assertThat(FundingSettlementDue.isDue(stored, next, hourly, Instant.parse("2026-09-24T10:00:00Z")))
                .isTrue();
        assertThat(FundingSettlementDue.isDue(stored, next, hourly, Instant.parse("2026-09-24T09:59:59.999Z")))
                .isFalse();
    }

    /** A stored settlement exactly at the expected instant counts as the expected one. */
    @Test
    void BR11_aSettlementStoredExactlyOnTheInstant_isNotDueAgain() {
        assertThat(FundingSettlementDue.isDue(
                        Instant.parse("2026-09-24T10:00:00Z"),
                        Instant.parse("2026-09-24T11:00:00Z"),
                        Optional.of(Duration.ofHours(1)),
                        NOW))
                .isFalse();
    }

    @Test
    void BR11_aZeroOrNegativeInterval_isRefused() {
        Instant next = Instant.parse("2026-09-24T11:00:00Z");
        assertThatThrownBy(() -> FundingSettlementDue.isDue(NOW, next, Optional.of(Duration.ZERO), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FundingSettlementDue.isDue(NOW, next, Optional.of(Duration.ofHours(-1)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BR11_missingArguments_areRefused() {
        Optional<Duration> hourly = Optional.of(Duration.ofHours(1));
        assertThatThrownBy(() -> FundingSettlementDue.isDue(NOW, null, hourly, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FundingSettlementDue.isDue(NOW, NOW, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FundingSettlementDue.isDue(NOW, NOW, hourly, null))
                .isInstanceOf(NullPointerException.class);
    }
}
