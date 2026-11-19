package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The stream's signals of a market's health (D-158): the latest live data, and the start of the unbroken run, which a
 * silence of {@code reconnectingAfter} (10 s) restarts.
 *
 * <p>Rule: NSF-03; D-158.
 */
class MarketLivenessTest {

    private static final Instant START = Instant.parse("2026-11-19T08:00:00Z");

    private final MutableTestClock clock = new MutableTestClock(START);

    private final MarketLiveness liveness = new MarketLiveness(
            new MarketHealthProperties(true, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)),
            clock);

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

    /**
     * E5: one connection of three silent leaves the market live, its pairs paused once silent pause-after (30 s); the
     * pairs of the connections that deliver are not.
     */
    @Test
    void D158_oneSilentConnectionOfThree_leavesTheMarketLive_andPausesItsPairs() {
        liveness.connections(
                MarketType.SPOT,
                Map.of("SPOT-0", List.of("BTCUSDT"), "SPOT-1", List.of("ETHUSDT"), "SPOT-2", List.of("BNBUSDT")));
        clock.advance(Duration.ofSeconds(40));
        liveness.record(MarketType.SPOT, "SPOT-0", clock.instant());
        liveness.record(MarketType.SPOT, "SPOT-1", clock.instant());

        assertThat(liveness.lastLiveAt(MarketType.SPOT)).isEqualTo(clock.instant());
        assertThat(liveness.silentSymbols(MarketType.SPOT)).containsExactly("BNBUSDT");
        clock.advance(Duration.ofSeconds(5));
        liveness.record(MarketType.SPOT, "SPOT-2", clock.instant());
        assertThat(liveness.silentSymbols(MarketType.SPOT)).isEmpty();
    }

    /**
     * E5: more than pause-ratio (half) of the pairs silent reads as the market silent since the pairs that make the
     * majority were last live, so the monitor pauses it; the share counts pairs, not connections.
     */
    @Test
    void D158_moreThanHalfOfThePairsSilent_readsAsTheMarketSilent() {
        liveness.connections(
                MarketType.FUTURES,
                Map.of("FUTURES-0", List.of("BTCUSDT", "ETHUSDT", "BNBUSDT"), "FUTURES-1", List.of("SOLUSDT")));
        liveness.record(MarketType.FUTURES, "FUTURES-0", START.plusSeconds(5));
        clock.advance(Duration.ofSeconds(40));
        liveness.record(MarketType.FUTURES, "FUTURES-1", clock.instant());

        assertThat(liveness.lastLiveAt(MarketType.FUTURES))
                .as("three of four pairs silent since 5 s")
                .isEqualTo(START.plusSeconds(5));
        assertThat(liveness.silentSymbols(MarketType.FUTURES))
                .containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT", "BNBUSDT");

        liveness.connections(MarketType.SPOT, Map.of("SPOT-0", List.of("BTCUSDT"), "SPOT-1", List.of("ETHUSDT")));
        clock.advance(Duration.ofSeconds(40));
        liveness.record(MarketType.SPOT, "SPOT-1", clock.instant());
        assertThat(liveness.lastLiveAt(MarketType.SPOT))
                .as("one of two is not more than half")
                .isEqualTo(clock.instant());
    }

    /**
     * E5: after a break the market's run restarts once enough pairs deliver again, from when the last of them that
     * makes the share came back; a message of no registered connection (the public book streams) counts for none.
     */
    @Test
    void D158_theRunOfAMarket_restartsWhenEnoughOfItsPairsDeliverAgain() {
        liveness.connections(MarketType.SPOT, Map.of("SPOT-0", List.of("BTCUSDT"), "SPOT-1", List.of("ETHUSDT")));
        clock.advance(Duration.ofSeconds(60));
        liveness.record(MarketType.SPOT, null, clock.instant());
        assertThat(liveness.lastLiveAt(MarketType.SPOT)).isEqualTo(START);

        liveness.record(MarketType.SPOT, "SPOT-0", clock.instant());
        clock.advance(Duration.ofSeconds(3));
        liveness.record(MarketType.SPOT, "SPOT-0", clock.instant());
        liveness.record(MarketType.SPOT, "SPOT-1", clock.instant());

        assertThat(liveness.lastLiveAt(MarketType.SPOT)).isEqualTo(clock.instant());
        assertThat(liveness.liveSince(MarketType.SPOT)).isEqualTo(START.plusSeconds(60));
    }
}
