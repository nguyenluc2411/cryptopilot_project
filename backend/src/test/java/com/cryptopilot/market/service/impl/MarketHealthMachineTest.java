package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.HealthSignals;
import com.cryptopilot.market.model.enums.MarketHealthState;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The transitions of a market's data health (D-158), over every state and the signals that move it: reconnecting after
 * 10 s without data, paused after 30 s or when the REST API is also down, recovering only after 15 s of unbroken data
 * with the REST API up, live only once recovery is done, and maintenance over everything.
 *
 * <p>Rule: NSF-03; D-158.
 */
class MarketHealthMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");

    private final MarketHealthMachine machine = new MarketHealthMachine(
            new MarketHealthProperties(true, Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(15)));

    /**
     * One row per case: the state now, seconds since the last live data, seconds the current live run has lasted
     * (ignored when the data is silent), whether REST answers, whether recovery is done, the expected next state.
     */
    @ParameterizedTest(name = "{0}, silent {1}s, live run {2}s, rest {3}, recovered {4} -> {5}")
    @CsvSource({
        // Live
        "LIVE,          0,   600, true,  false, LIVE",
        "LIVE,          9,   600, true,  false, LIVE",
        "LIVE,         10,    10, true,  false, RECONNECTING",
        "LIVE,         29,    29, true,  false, RECONNECTING",
        "LIVE,         30,    30, true,  false, PAUSED",
        "LIVE,          9,   600, false, false, LIVE",
        // Reconnecting
        "RECONNECTING,  3,     3, true,  false, LIVE",
        "RECONNECTING, 20,    20, true,  false, RECONNECTING",
        "RECONNECTING, 20,    20, false, false, PAUSED",
        "RECONNECTING, 30,    30, true,  false, PAUSED",
        // Paused: needs data for 15 s without a break and REST up
        "PAUSED,       40,    40, true,  false, PAUSED",
        "PAUSED,        1,    10, true,  false, PAUSED",
        "PAUSED,        1,    15, true,  false, RECOVERING",
        "PAUSED,        1,    60, false, false, PAUSED",
        "PAUSED,        1,    60, true,  true,  RECOVERING",
        // Recovering: live only once done; a break pauses again
        "RECOVERING,    1,    60, true,  false, RECOVERING",
        "RECOVERING,    1,    60, true,  true,  LIVE",
        "RECOVERING,   10,    10, true,  false, PAUSED",
        "RECOVERING,   10,    10, true,  true,  PAUSED",
        "RECOVERING,    1,    60, false, false, RECOVERING",
        "RECOVERING,    1,    60, false, true,  LIVE",
        // Maintenance ended: through recovery, or paused while the data is lost
        "MAINTENANCE,   1,    60, true,  true,  RECOVERING",
        "MAINTENANCE,   1,    60, true,  false, RECOVERING",
        "MAINTENANCE,   1,     5, true,  true,  PAUSED",
        "MAINTENANCE,  40,    40, true,  true,  PAUSED",
        "MAINTENANCE,   1,    60, false, true,  PAUSED",
    })
    void D158_theNextState_followsTheSignals(
            MarketHealthState current,
            long silentSeconds,
            long liveRunSeconds,
            boolean restAvailable,
            boolean recoveryDone,
            MarketHealthState expected) {
        Instant lastLiveAt = NOW.minusSeconds(silentSeconds);
        HealthSignals signals = new HealthSignals(
                lastLiveAt,
                NOW.minusSeconds(Math.max(liveRunSeconds, silentSeconds)),
                restAvailable,
                false,
                recoveryDone);

        assertThat(machine.next(current, signals, NOW)).isEqualTo(expected);
    }

    /** E13: maintenance closes the market from every state, whatever the data does. */
    @ParameterizedTest
    @EnumSource(MarketHealthState.class)
    void D158_maintenance_holdsEveryStateClosed(MarketHealthState current) {
        HealthSignals live = new HealthSignals(NOW, NOW.minusSeconds(600), true, true, true);
        HealthSignals lost = new HealthSignals(NOW.minusSeconds(600), NOW.minusSeconds(600), false, true, false);

        assertThat(machine.next(current, live, NOW)).isEqualTo(MarketHealthState.MAINTENANCE);
        assertThat(machine.next(current, lost, NOW)).isEqualTo(MarketHealthState.MAINTENANCE);
    }

    /** E3: a market flapping between paused and recovering stays in one incident until it is live again. */
    @Test
    void D158_anIncident_opensOnClosing_andClosesOnlyWhenLiveAgain() {
        assertThat(MarketHealthMachine.opensIncident(MarketHealthState.LIVE, MarketHealthState.PAUSED))
                .isTrue();
        assertThat(MarketHealthMachine.opensIncident(MarketHealthState.RECONNECTING, MarketHealthState.PAUSED))
                .isTrue();
        assertThat(MarketHealthMachine.opensIncident(MarketHealthState.LIVE, MarketHealthState.MAINTENANCE))
                .isTrue();
        assertThat(MarketHealthMachine.opensIncident(MarketHealthState.LIVE, MarketHealthState.RECONNECTING))
                .isFalse();
        assertThat(MarketHealthMachine.opensIncident(MarketHealthState.RECOVERING, MarketHealthState.PAUSED))
                .as("a relapse is the same incident")
                .isFalse();
        assertThat(MarketHealthMachine.opensIncident(MarketHealthState.PAUSED, MarketHealthState.MAINTENANCE))
                .as("maintenance during an outage is the same incident")
                .isFalse();

        assertThat(MarketHealthMachine.closesIncident(MarketHealthState.RECOVERING, MarketHealthState.LIVE))
                .isTrue();
        assertThat(MarketHealthMachine.closesIncident(MarketHealthState.PAUSED, MarketHealthState.RECOVERING))
                .isFalse();
        assertThat(MarketHealthMachine.closesIncident(MarketHealthState.RECONNECTING, MarketHealthState.LIVE))
                .isFalse();
    }

    /** Trading is open while live or reconnecting only. */
    @Test
    void D158_tradingIsOpen_onlyLiveOrReconnecting() {
        assertThat(MarketHealthState.LIVE.tradingOpen()).isTrue();
        assertThat(MarketHealthState.RECONNECTING.tradingOpen()).isTrue();
        assertThat(MarketHealthState.PAUSED.tradingOpen()).isFalse();
        assertThat(MarketHealthState.RECOVERING.tradingOpen()).isFalse();
        assertThat(MarketHealthState.MAINTENANCE.tradingOpen()).isFalse();
    }
}
