package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What is kept of the failure that made the backfill drop a gap: a one-line summary that fits the column, never a
 * stack trace or the rest of a driver message.
 *
 * <p>Rule: NSF-02.
 */
class DroppedGapServiceImplTest {

    @Test
    void NSF02_theSummary_isTheTypeAndTheFirstLineOfTheMessage() {
        IllegalStateException failure = new IllegalStateException("  candle refused  \nDetail: Key (open_time)=(…)");

        assertThat(DroppedGapServiceImpl.summaryOf(failure)).isEqualTo("IllegalStateException: candle refused");
    }

    @Test
    void NSF02_aFailureWithoutAMessage_isSummarisedByItsType() {
        assertThat(DroppedGapServiceImpl.summaryOf(new NullPointerException())).isEqualTo("NullPointerException");
        assertThat(DroppedGapServiceImpl.summaryOf(new IllegalStateException("\n")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void NSF02_aLongMessage_isCutToTheColumn() {
        String summary = DroppedGapServiceImpl.summaryOf(new IllegalStateException("x".repeat(2000)));

        assertThat(summary).hasSize(DroppedGapServiceImpl.ERROR_LENGTH).startsWith("IllegalStateException: x");
    }
}
