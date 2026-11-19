package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The stream's signals of a market's health (D-158): the latest live data, and the start of the unbroken run, which a
 * silence of {@code reconnectingAfter} (10 s) restarts.
 *
 * <p>Rule: NSF-03; D-158.
 */
class MarketLivenessTest {

    private static final Instant START = Instant.parse("2026-11-19T08:00:00Z");

    private final MarketLiveness liveness = new MarketLiveness(
            new MarketHealthProperties(true, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
            new MutableTestClock(START));

    @Test
    void D158_beforeAnyMessage_bothSignalsReadTheStart() {
        assertThat(liveness.lastLiveAt(MarketType.SPOT)).isEqualTo(START);
        assertThat(liveness.liveSince(MarketType.FUTURES)).isEqualTo(START);
    }

    @Test
    void D158_aRun_goesOnThroughShortSilences_andRestartsAfterABreak() {
        liveness.record(MarketType.FUTURES, START.plusSeconds(5));
        liveness.record(MarketType.FUTURES, START.plusSeconds(14));
        assertThat(liveness.liveSince(MarketType.FUTURES)).isEqualTo(START);
        assertThat(liveness.lastLiveAt(MarketType.FUTURES)).isEqualTo(START.plusSeconds(14));

        liveness.record(MarketType.FUTURES, START.plusSeconds(24));
        assertThat(liveness.liveSince(MarketType.FUTURES))
                .as("10 s apart: a break")
                .isEqualTo(START.plusSeconds(24));

        liveness.record(MarketType.FUTURES, START.plusSeconds(20));
        assertThat(liveness.lastLiveAt(MarketType.FUTURES))
                .as("never moves back")
                .isEqualTo(START.plusSeconds(24));
        assertThat(liveness.lastLiveAt(MarketType.SPOT))
                .as("the other market untouched")
                .isEqualTo(START);
    }
}
