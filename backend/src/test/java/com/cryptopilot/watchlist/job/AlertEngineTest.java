package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.model.PriceAlert;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The engine around the books: the stream thread never waits on a full partition, and a trigger the database refuses
 * makes the engine read the alert again. {@link AlertTriggerService} is mocked; the books run on a test clock.
 *
 * <p>Rule: NSF-06, BR-19; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-87, D-89.
 */
class AlertEngineTest {

    private static final Instant T0 = Instant.parse("2026-10-05T08:10:00Z");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c002");
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final MutableTestClock clock = new MutableTestClock(T0);
    private final AlertTriggerService triggers = mock(AlertTriggerService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AlertEngine.class);

    private AlertEngine engine;

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
        }
        logger.detachAppender(logs);
    }

    @Test
    void NSF06_aFullPartitionQueue_neverBlocksTheStream_andWarnsOncePerMinute() {
        when(triggers.activePriceAlerts()).thenReturn(List.of());
        // The partition's thread never drains its queue, so the second pair already finds it full.
        engine = new AlertEngine(triggers, properties(1), clock, idleThreads());
        engine.start();

        for (int i = 0; i < 5; i++) {
            engine.onMinuteKline(kline(UUID.randomUUID(), "100", T0.plusSeconds(i)));
        }

        assertThat(logs.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("queue is full"));
        verify(triggers, never()).tryTrigger(any());
    }

    @Test
    void NSF06_aTriggerTheDatabaseRefuses_readsTheAlertAgain_andTheBookFollowsTheStoredRule() {
        PriceAlert held = alert(UUID.randomUUID(), "100", 1);
        // Edited meanwhile: a higher target at the next version.
        PriceAlert edited = alert(held.alertId(), "200", 2);
        when(triggers.activePriceAlerts()).thenReturn(List.of(held));
        when(triggers.activePriceAlert(held.alertId())).thenReturn(Optional.of(edited));
        when(triggers.tryTrigger(any())).thenReturn(false, true);
        engine = new AlertEngine(
                triggers, properties(1000), clock, Thread.ofVirtual().factory());
        engine.start();

        engine.onMinuteKline(kline(held.pairId(), "101", T0));
        verify(triggers, timeout(WAIT.toMillis())).activePriceAlert(held.alertId());

        clock.advance(Duration.ofSeconds(1));
        engine.onMinuteKline(kline(held.pairId(), "201", T0.plusSeconds(1)));

        verify(triggers, timeout(WAIT.toMillis()))
                .tryTrigger(argThat(hit -> hit.alert().version() == 2
                        && hit.alert().threshold().compareTo(new BigDecimal("200")) == 0
                        && hit.observedValue().compareTo(new BigDecimal("201")) == 0));
        assertThat(engine.holds(held.alertId())).isTrue();
    }

    private static AlertEngineProperties properties(int queueCapacity) {
        return new AlertEngineProperties(
                true,
                1,
                queueCapacity,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                new AlertEngineProperties.ExpirySweep(false, "0 * * * * *", "UTC"));
    }

    /** Threads that run nothing: the partition's queue is never drained. */
    private static ThreadFactory idleThreads() {
        return ignored -> Thread.ofVirtual().unstarted(() -> {});
    }

    private static MinuteKline kline(UUID pairId, String close, Instant eventTime) {
        BigDecimal price = new BigDecimal(close);
        return new MinuteKline(MarketType.SPOT, pairId, T0, price, price, price, false, eventTime);
    }

    private static PriceAlert alert(UUID alertId, String threshold, long version) {
        return new PriceAlert(
                alertId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                "BTCUSDT",
                MarketType.SPOT,
                ConditionOperator.GREATER_THAN,
                new BigDecimal(threshold),
                TriggerMode.EVERY_TIME,
                5,
                null,
                true,
                false,
                false,
                0,
                null,
                null,
                version);
    }
}
