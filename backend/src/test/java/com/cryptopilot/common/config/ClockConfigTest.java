package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    private final Clock clock = new ClockConfig().clock();

    @Test
    void clock_runsInUtc_soCandleAndFundingBoundariesDoNotDependOnTheHost() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void clock_readsTheCurrentTime() {
        Instant before = Instant.now();

        Instant now = clock.instant();

        assertThat(now).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }
}
