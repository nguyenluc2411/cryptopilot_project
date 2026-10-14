package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.IndicatorLevels;
import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.service.IndicatorAlertService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The job only hands the candle on: not where the alert engine is off, and a failure stays in the job.
 *
 * <p>Rule: NSF-06; SRS 3.4.4.
 */
class IndicatorAlertJobTest {

    private static final IndicatorsComputed CANDLE = new IndicatorsComputed(
            UUID.randomUUID(),
            "BTCUSDT",
            MarketType.SPOT,
            "1h",
            Instant.parse("2026-10-14T08:00:00Z"),
            IndicatorLevels.NONE,
            IndicatorLevels.NONE);

    private final IndicatorAlertService service = mock(IndicatorAlertService.class);

    @Test
    void NSF06_whereTheEngineRuns_theCandleIsEvaluated() {
        new IndicatorAlertJob(service, properties(true)).onIndicatorsComputed(CANDLE);

        verify(service).evaluate(CANDLE);
    }

    @Test
    void NSF06_whereTheEngineIsOff_nothingIsEvaluated() {
        new IndicatorAlertJob(service, properties(false)).onIndicatorsComputed(CANDLE);

        verify(service, never()).evaluate(any());
    }

    @Test
    void NSF06_aFailure_doesNotReachThePublisher() {
        when(service.evaluate(any())).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> new IndicatorAlertJob(service, properties(true)).onIndicatorsComputed(CANDLE))
                .doesNotThrowAnyException();
    }

    private static AlertEngineProperties properties(boolean enabled) {
        return new AlertEngineProperties(
                enabled,
                1,
                10,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                new AlertEngineProperties.ExpirySweep(false, "0 * * * * *", "UTC"));
    }
}
