package com.cryptopilot.market.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The health thresholds must fit together (D-158): a paused market can only recover after its data has flowed longer
 * than a break, or a single gap would both break the run and count as stable.
 *
 * <p>Rule: NSF-03; D-158.
 */
class MarketHealthPropertiesTest {

    @Test
    void D158_stableBeforeRecover_mustBeLongerThanReconnectingAfter() {
        assertThatCode(() -> properties(Duration.ofSeconds(11))).doesNotThrowAnyException();
        assertThatThrownBy(() -> properties(Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stable-before-recover must be longer than reconnecting-after");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
    }

    private static MarketHealthProperties properties(Duration stableBeforeRecover) {
        return new MarketHealthProperties(true, Duration.ofSeconds(10), Duration.ofSeconds(30), stableBeforeRecover);
    }
}
