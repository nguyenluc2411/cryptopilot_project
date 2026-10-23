package com.cryptopilot.trading.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The declared defaults of the restart replay, bound in a bare context so the binding itself is under test.
 *
 * <p>Rule: NSF-07; A-04; Q-34.
 */
class MatchingPropertiesTest {

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(BindMatchingProperties.class);

    /** Q-34: the replay reaches back seven days, the default expiry of a plan, unless configured otherwise. */
    @Test
    void Q34_theReplayWindow_defaultsToSevenDays() {
        contexts.run(context -> assertThat(
                        context.getBean(MatchingProperties.class).replay().maxWindow())
                .isEqualTo(Duration.ofDays(7)));
    }

    @Test
    void Q34_theReplayWindow_canBeConfigured() {
        contexts.withPropertyValues("cryptopilot.trading.matching.replay.max-window=24h")
                .run(context -> assertThat(context.getBean(MatchingProperties.class)
                                .replay()
                                .maxWindow())
                        .isEqualTo(Duration.ofHours(24)));
    }

    @EnableConfigurationProperties(MatchingProperties.class)
    static class BindMatchingProperties {}
}
