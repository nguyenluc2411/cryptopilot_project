package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.watchlist.AlertTestData;
import com.cryptopilot.watchlist.AlertTriggeredRecorder;
import com.cryptopilot.watchlist.dto.request.CreateAlertRequest;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The engine in the application: an alert created through the service reaches the engine after its commit, the
 * 1-minute candle updates are evaluated on their close, and the trigger is stored and published once.
 *
 * <p>The engine is switched on for this context only; the stream is not, so the test hands the candles to the engine
 * as the stream would. Nothing is rolled back: the trigger commits on the engine's own thread, and
 * {@link AlertTestData#clear()} deletes the rows.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-87, D-89.
 */
@SpringBootTest(properties = "cryptopilot.alert.enabled=true")
@Import({TestcontainersConfig.class, AlertTriggeredRecorder.class, AlertEngineIntegrationTest.TestClock.class})
class AlertEngineIntegrationTest {

    /** Inside an hour, so the two updates fall in one 1h candle. */
    private static final Instant NOW = Instant.parse("2026-10-05T08:10:15Z");

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private AlertEngine engine;

    @Autowired
    private AlertService alerts;

    @Autowired
    private AlertTriggeredRecorder recorder;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private JdbcClient sql;

    private AlertTestData data;

    @BeforeEach
    void resetTheClock() {
        clock.set(NOW);
        data = new AlertTestData(sql);
    }

    @AfterEach
    void removeRows() {
        data.clear();
    }

    @Test
    void NSF06_aPriceCrossingAnAlertCreatedThroughTheService_isTriggeredOnce_andPublishedOnce() throws Exception {
        UUID trader = data.trader();
        UUID pair = data.pair("EN" + System.nanoTime() % 1_000_000_000L + "USDT");
        UUID cross = create(trader, pair, ConditionOperator.CROSS_ABOVE, "100");
        // Fires on the first update already, which tells the test that update has been evaluated.
        UUID probe = create(trader, pair, ConditionOperator.GREATER_THAN, "98");
        assertThat(engine.isRunning()).isTrue();
        assertThat(engine.holds(cross)).as("held after the commit").isTrue();

        engine.onMinuteKline(kline(pair, "99", clock.instant()));
        recorder.await(probe, 1, WAIT);
        assertThat(data.triggerCount(cross))
                .as("no previous price, so no cross yet")
                .isZero();

        clock.advance(Duration.ofSeconds(1));
        engine.onMinuteKline(kline(pair, "101", clock.instant()));

        AlertTriggered event = recorder.await(cross, 1, WAIT).getFirst();
        assertThat(event.observedValue()).isEqualByComparingTo("101");
        assertThat(event.threshold()).isEqualByComparingTo("100");
        assertThat(event.condition()).isEqualTo(ConditionOperator.CROSS_ABOVE);
        assertThat(event.triggerCount()).isOne();
        assertThat(event.triggeredAt()).isEqualTo(clock.instant());
        assertThat(data.triggerCount(cross)).isOne();
        assertThat(data.status(cross)).isEqualTo("TRIGGERED");
        assertThat(data.lastTriggeredAt(cross)).isEqualTo(clock.instant());
        assertThat(recorder.of(cross)).hasSize(1);
    }

    private UUID create(UUID trader, UUID pair, ConditionOperator condition, String threshold) {
        return alerts.create(
                        trader,
                        new CreateAlertRequest(
                                pair,
                                MarketType.SPOT,
                                AlertType.PRICE,
                                null,
                                null,
                                condition,
                                new BigDecimal(threshold),
                                TriggerMode.ONCE,
                                null,
                                false,
                                false,
                                null))
                .id();
    }

    private static MinuteKline kline(UUID pair, String close, Instant eventTime) {
        BigDecimal price = new BigDecimal(close);
        return new MinuteKline(
                MarketType.SPOT,
                pair,
                eventTime.truncatedTo(ChronoUnit.MINUTES),
                price,
                price,
                price,
                false,
                eventTime);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {

        @Bean
        MutableTestClock testClock() {
            return new MutableTestClock(NOW);
        }

        @Bean
        @Primary
        Clock clockUnderTest(MutableTestClock testClock) {
            return testClock;
        }
    }
}
