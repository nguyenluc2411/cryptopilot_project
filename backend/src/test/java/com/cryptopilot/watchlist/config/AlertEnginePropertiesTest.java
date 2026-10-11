package com.cryptopilot.watchlist.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The engine's durations must be positive: a zero throttle would spin the partitions, a zero staleness would forget
 * every previous price on a reconnect, a zero tombstone would let any stale read of a removed alert back in, and a zero
 * stop timeout would not wait for the partitions at all.
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
            assertThat(properties.tombstoneTtl()).isEqualTo(properties.staleness());
            assertThat(properties.stopTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void NSF06_theTombstoneTtl_isSetApartFromTheStaleness() {
        context.withPropertyValues("cryptopilot.alert.tombstone-ttl=2m").run(started -> {
            AlertEngineProperties properties = started.getBean(AlertEngineProperties.class);
            assertThat(properties.tombstoneTtl()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.staleness()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "500ms"})
    void NSF06_aTombstoneTtlBelowOneSecond_stopsTheContext(String ttl) {
        context.withPropertyValues("cryptopilot.alert.tombstone-ttl=" + ttl)
                .run(started -> assertThat(started).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void NSF06_aStopTimeoutNotPositive_stopsTheContext(String timeout) {
        context.withPropertyValues("cryptopilot.alert.stop-timeout=" + timeout)
                .run(started -> assertThat(started).hasFailed());
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
