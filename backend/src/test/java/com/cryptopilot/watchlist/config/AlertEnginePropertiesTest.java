package com.cryptopilot.watchlist.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The engine's durations must be positive: a zero throttle would spin the partitions, a zero staleness would forget
 * every previous price on a reconnect, a zero tombstone would let any stale read of a removed alert back in, and a zero
 * stop timeout would not wait for the partitions at all. The tombstone must also outlast the longest read of an alert
 * the datasource allows.
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
    @ValueSource(strings = {"0s", "-1s", "500ms", "999ms"})
    void NSF06_aTombstoneTtlBelowOneSecond_stopsTheContext(String ttl) {
        context.withPropertyValues("cryptopilot.alert.tombstone-ttl=" + ttl)
                .run(started -> assertThat(started).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void NSF06_aTombstoneTtlOfOneSecond_isTaken_whenTheReadsAreBoundBelowIt() {
        context.withPropertyValues(
                        "cryptopilot.alert.tombstone-ttl=1s", "spring.datasource.hikari.connection-timeout=250")
                .run(started -> assertThat(
                                started.getBean(AlertEngineProperties.class).tombstoneTtl())
                        .isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    void NSF06_aTombstoneTtlNotLongerThanTheDefaultConnectionWait_stopsTheContext() {
        context.withPropertyValues("cryptopilot.alert.tombstone-ttl=30s").run(started -> assertThat(started)
                .getFailure()
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("cryptopilot.alert.tombstone-ttl (PT30S)")
                .hasMessageContaining("connection-timeout (PT30S)"));
    }

    @Test
    void NSF06_aTombstoneTtlEqualToTheConnectionWaitPlusTheQueryTimeout_stopsTheContext() {
        context.withPropertyValues(
                        "cryptopilot.alert.tombstone-ttl=1m",
                        "spring.datasource.hikari.connection-timeout=45000",
                        "spring.jpa.properties.jakarta.persistence.query.timeout=15000")
                .run(started -> assertThat(started).getFailure().hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @Test
    void NSF06_aTombstoneTtlOneMillisecondLongerThanTheLongestRead_isTaken() {
        context.withPropertyValues(
                        "cryptopilot.alert.tombstone-ttl=60001ms",
                        "spring.datasource.hikari.connection-timeout=45000",
                        "spring.jpa.properties.jakarta.persistence.query.timeout=15000")
                .run(started -> assertThat(started.getBean(AlertReadBound.class).longest())
                        .isEqualTo(Duration.ofMinutes(1)));
    }

    @Test
    void NSF06_aConnectionWaitWithoutLimit_stopsTheContext() {
        context.withPropertyValues("spring.datasource.hikari.connection-timeout=0")
                .run(started -> assertThat(started).getFailure().hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void NSF06_aStopTimeoutNotPositive_stopsTheContext(String timeout) {
        context.withPropertyValues("cryptopilot.alert.stop-timeout=" + timeout)
                .run(started -> assertThat(started).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void NSF06_aStopTimeoutOfOneMillisecond_isTaken() {
        context.withPropertyValues("cryptopilot.alert.stop-timeout=1ms").run(started -> assertThat(
                        started.getBean(AlertEngineProperties.class).stopTimeout())
                .isEqualTo(Duration.ofMillis(1)));
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
