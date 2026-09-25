package com.cryptopilot.market.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The one identity of a funding settlement instant: rounded to the nearest minute, so the stamps the exchange
 * gives one settlement are one instant, and the next settlement is read from half a minute after it.
 *
 * <p>Rule: BR-11, BR-37; NSF-04.
 */
class FundingTimesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "2026-09-24T08:00:00Z, 2026-09-24T08:00:00Z",
        "2026-09-24T08:00:00.005Z, 2026-09-24T08:00:00Z",
        "2026-09-24T07:59:59.998Z, 2026-09-24T08:00:00Z",
        "2026-09-24T08:00:29.999Z, 2026-09-24T08:00:00Z",
        "2026-09-24T08:00:30Z, 2026-09-24T08:01:00Z",
        "2026-09-24T07:59:30Z, 2026-09-24T08:00:00Z",
        "1969-12-31T23:59:59.990Z, 1970-01-01T00:00:00Z"
    })
    void BR37_aSettlementInstant_isRoundedToTheNearestMinute(Instant received, Instant stored) {
        assertThat(FundingTimes.normalize(received)).isEqualTo(stored);
    }

    /** Every stamp of the stored settlement lies before {@code after}; nothing of the next one does. */
    @Test
    void NSF04_after_separatesTheStoredSettlementFromTheNext() {
        Instant after = FundingTimes.after(Instant.parse("2026-09-24T08:00:00.005Z"));

        assertThat(after).isEqualTo(Instant.parse("2026-09-24T08:00:30Z"));
        assertThat(FundingTimes.normalize(after.minusMillis(1))).isEqualTo(Instant.parse("2026-09-24T08:00:00Z"));
        assertThat(FundingTimes.normalize(after)).isAfter(Instant.parse("2026-09-24T08:00:00Z"));
    }

    @Test
    void BR37_missingInstants_areRefused() {
        assertThatThrownBy(() -> FundingTimes.normalize(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> FundingTimes.after(null)).isInstanceOf(NullPointerException.class);
    }
}
