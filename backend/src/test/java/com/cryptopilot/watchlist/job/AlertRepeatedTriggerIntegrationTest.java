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
import java.util.List;
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
 * An EVERY_TIME alert that fires again: each trigger adds one to {@code trigger_count}, and the event of the n-th
 * trigger carries n. The counts are what SRS 3.4.4 asks for (each trigger increases the count by one), not what the
 * engine printed.
 *
 * <p>Rule: BR-19, BR-20; SRS 3.4.4; NSF-06.
 */
@SpringBootTest(properties = "cryptopilot.alert.enabled=true")
@Import({TestcontainersConfig.class, AlertTriggeredRecorder.class, AlertRepeatedTriggerIntegrationTest.TestClock.class})
class AlertRepeatedTriggerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:10:15Z");

    private static final Duration WAIT = Duration.ofSeconds(10);

    /** The shortest cooldown BR-19 allows. */
    private static final int COOLDOWN_MINUTES = 1;

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
    void BR19_anEveryTimeAlertCrossedThreeTimes_publishesTriggerCounts1Then2Then3() throws Exception {
        UUID trader = data.trader();
        UUID pair = data.pair("RT" + System.nanoTime() % 1_000_000_000L + "USDT");
        UUID cross =
                create(trader, pair, ConditionOperator.CROSS_ABOVE, "100", TriggerMode.EVERY_TIME, COOLDOWN_MINUTES);

        for (int n = 1; n <= 3; n++) {
            // Below the target first, so the next price crosses it again (BR-20); a ONCE probe below 100 tells the
            // test this price was evaluated before the crossing one is sent.
            UUID below = create(trader, pair, ConditionOperator.LESS_THAN, "100", TriggerMode.ONCE, null);
            engine.onMinuteKline(kline(pair, "99", clock.instant()));
            recorder.await(below, 1, WAIT);

            clock.advance(Duration.ofSeconds(1));
            engine.onMinuteKline(kline(pair, "101", clock.instant()));

            List<AlertTriggered> events = recorder.await(cross, n, WAIT);
            assertThat(events).hasSize(n);
            assertThat(events.getLast().triggerCount())
                    .as("event of trigger %d", n)
                    .isEqualTo(n);
            assertThat(events.getLast().triggeredAt()).isEqualTo(clock.instant());
            assertThat(data.triggerCount(cross))
                    .as("stored count after trigger %d", n)
                    .isEqualTo(n);

            // Well past the cooldown, so the next crossing is not held back by it.
            clock.advance(Duration.ofMinutes(COOLDOWN_MINUTES + 1L));
        }

        assertThat(recorder.of(cross)).extracting(AlertTriggered::triggerCount).containsExactly(1, 2, 3);
        assertThat(data.status(cross)).as("only ONCE ends in TRIGGERED").isEqualTo("ACTIVE");
    }

    private UUID create(
            UUID trader,
            UUID pair,
            ConditionOperator condition,
            String threshold,
            TriggerMode mode,
            Integer cooldownMinutes) {
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
                                mode,
                                cooldownMinutes,
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
