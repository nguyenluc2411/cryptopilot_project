package com.cryptopilot.watchlist.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The engine's durations must be positive: a zero throttle would spin the partitions, a zero staleness would forget
 * every previous price on a reconnect.
 *
 * <p>Rule: NSF-06; SRS 3.4.4; D-87.
 */
class AlertEnginePropertiesTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(AlertEngineConfig.class);

    @Test
    void NSF06_theDefaults_bind() {
        context.run(started -> {
            AlertEngineProperties properties = started.getBean(AlertEngineProperties.class);
            assertThat(properties.throttle()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.staleness()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void NSF06_aZeroThrottle_stopsTheContext() {
        context.withPropertyValues("cryptopilot.alert.throttle=0s")
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void NSF06_aZeroStaleness_stopsTheContext() {
        context.withPropertyValues("cryptopilot.alert.staleness=0s")
                .run(started -> assertThat(started).hasFailed());
    }
}
