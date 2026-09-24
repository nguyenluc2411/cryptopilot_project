package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * NSF-03's back-off: 1 s, 2 s, 4 s … up to 60 s, and the jitter only ever shortens a wait.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 7.1 step 6.
 */
class ReconnectBackoffTest {

    @Test
    void NSF03_withoutJitter_theWaitDoublesFromOneSecondUpToSixty() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60), 20, () -> 0.0);

        assertThat(java.util.stream.IntStream.range(0, 9).mapToObj(backoff::delay))
                .containsExactly(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(16),
                        Duration.ofSeconds(32),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(60));
    }

    /** Even after a thousand failures the wait stays at the ceiling rather than overflowing. */
    @Test
    void NSF03_aVeryLongOutage_staysAtTheCeiling() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60), 20, () -> 0.0);

        assertThat(backoff.delay(1000)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void NSF03_theJitter_shortensAWaitByAtMostItsShare() {
        ReconnectBackoff most = new ReconnectBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60), 20, () -> 0.999999);
        ReconnectBackoff half = new ReconnectBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60), 20, () -> 0.5);

        assertThat(most.delay(6)).isGreaterThan(Duration.ofSeconds(48)).isLessThan(Duration.ofSeconds(60));
        assertThat(half.delay(0)).isEqualTo(Duration.ofMillis(900));
        assertThat(half.delay(10)).isEqualTo(Duration.ofSeconds(54));
    }
}
