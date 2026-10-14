package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.event.IndicatorLevels;
import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.watchlist.AlertTestData;
import com.cryptopilot.watchlist.AlertTriggeredRecorder;
import com.cryptopilot.watchlist.calculator.TriggerPolicy;
import com.cryptopilot.watchlist.event.AlertTriggered;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.IndicatorAlertHit;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import com.cryptopilot.watchlist.service.IndicatorAlertService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Indicator alerts in the application, on the database: a committed {@link IndicatorsComputed} fires the alerts of its
 * series once, a rolled-back one fires nothing, and the conditional update lets one candle fire an alert at most once,
 * also when two evaluations of it run at the same time.
 *
 * <p>Same context as {@link AlertEngineIntegrationTest}. Nothing is rolled back by the framework: the triggers commit
 * in their own transactions, and {@link AlertTestData#clear()} deletes the rows.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; TECHNICAL_DESIGN 7.9; D-89.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley,
 * "Optimistic Offline Lock" (the version read is the version written).
 */
@SpringBootTest(properties = "cryptopilot.alert.enabled=true")
@Import({TestcontainersConfig.class, AlertTriggeredRecorder.class, AlertEngineIntegrationTest.TestClock.class})
class IndicatorAlertIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:00:05Z");
    private static final Instant OPEN = Instant.parse("2026-10-14T08:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);
    private static final int RACES = 20;

    @Autowired
    private IndicatorAlertService indicatorAlerts;

    @Autowired
    private AlertTriggerService triggers;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AlertTriggeredRecorder recorder;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private JdbcClient sql;

    private AlertTestData data;
    private UUID trader;
    private String symbol;
    private UUID pair;
    private UUID row;

    @BeforeEach
    void insertRows() {
        clock.set(NOW);
        data = new AlertTestData(sql);
        trader = data.trader();
        symbol = "IA" + System.nanoTime() % 1_000_000_000L + "USDT";
        pair = data.pair(symbol);
        row = data.watch(trader, pair);
    }

    @AfterEach
    void removeRows() {
        data.clear();
    }

    @Test
    void NSF06_aCommittedCandle_firesAnRsiAlertOnce_andPublishesIt() {
        UUID alert = indicator("SPOT", "RSI_14", "1h", "CROSS_BELOW", "30", "ONCE", null);

        long start = System.nanoTime();
        transactions.executeWithoutResult(status -> events.publishEvent(spot(OPEN, rsi("29.5"), rsi("31"))));
        Duration latency = Duration.ofNanos(System.nanoTime() - start);

        // Evaluated and published on the committing thread: received when the commit returns, without waiting.
        assertThat(recorder.of(alert)).hasSize(1);
        assertThat(latency).isLessThan(Duration.ofSeconds(1));
        assertThat(data.triggerCount(alert)).isOne();
        assertThat(data.status(alert)).isEqualTo("TRIGGERED");
        assertThat(data.lastTriggeredAt(alert)).isEqualTo(NOW);
        assertThat(lastBar(alert)).isEqualTo(OPEN);
        AlertTriggered event = recorder.of(alert).getFirst();
        assertThat(recorder.of(alert)).hasSize(1);
        assertThat(event.type()).isEqualTo(AlertType.INDICATOR);
        assertThat(event.indicator()).isEqualTo(AlertIndicator.RSI_14);
        assertThat(event.condition()).isEqualTo(ConditionOperator.CROSS_BELOW);
        assertThat(event.threshold()).isEqualByComparingTo("30");
        assertThat(event.observedValue()).isEqualByComparingTo("29.5");
        assertThat(event.triggerCount()).isOne();
        assertThat(event.pairId()).isEqualTo(pair);
        assertThat(event.symbol()).isEqualTo(symbol);
    }

    @Test
    void NSF06_aCandleWhoseTransactionRollsBack_firesNothing() {
        UUID alert = indicator("SPOT", "RSI_14", "1h", "CROSS_BELOW", "30", "ONCE", null);

        transactions.executeWithoutResult(status -> {
            events.publishEvent(spot(OPEN, rsi("29.5"), rsi("31")));
            status.setRollbackOnly();
        });

        assertThat(data.triggerCount(alert)).isZero();
        assertThat(recorder.of(alert)).isEmpty();
    }

    @Test
    void D89_twoEvaluationsOfTheSameCandleAtOnce_recordOneTrigger() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int race = 0; race < RACES; race++) {
                UUID alert = indicator("SPOT", "MACD_CROSS", "1h", "CROSS_ABOVE", null, "EVERY_TIME", 1);
                IndicatorsComputed candle = spot(OPEN, histogram("0.2"), histogram("-0.1"));
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<Integer>> both = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    both.add(pool.submit(() -> {
                        start.await();
                        return indicatorAlerts.evaluate(candle);
                    }));
                }

                int recorded = both.get(0).get() + both.get(1).get();

                assertThat(recorded).as("race %d", race).isOne();
                assertThat(data.triggerCount(alert)).as("race %d", race).isOne();
                assertThat(recorder.of(alert)).as("race %d", race).hasSize(1);
            }
        }
    }

    @Test
    void BR19_theSameCandleAgain_orAnOlderOne_firesNothing_evenPastTheCooldown() {
        UUID alert = indicator("SPOT", "RSI_14", "1h", "GREATER_THAN", "70", "EVERY_TIME", 1);
        assertThat(indicatorAlerts.evaluate(spot(OPEN, rsi("75"), rsi("72")))).isOne();
        clock.advance(Duration.ofMinutes(2));

        assertThat(indicatorAlerts.evaluate(spot(OPEN, rsi("75"), rsi("72")))).isZero();
        assertThat(indicatorAlerts.evaluate(spot(OPEN.minus(HOUR), rsi("72"), rsi("71"))))
                .isZero();
        assertThat(indicatorAlerts.evaluate(spot(OPEN.plus(HOUR), rsi("76"), rsi("75"))))
                .isOne();

        assertThat(data.triggerCount(alert)).isEqualTo(2);
        assertThat(lastBar(alert)).isEqualTo(OPEN.plus(HOUR));
    }

    @Test
    void BR19_everyTime_waitsForTheCooldown_onTheNextCandle() {
        UUID alert = indicator("SPOT", "RSI_14", "15m", "LESS_THAN", "30", "EVERY_TIME", 20);
        Duration quarter = Duration.ofMinutes(15);
        assertThat(indicatorAlerts.evaluate(series(OPEN, "15m", rsi("25"), rsi("28"))))
                .isOne();

        clock.advance(quarter);
        assertThat(indicatorAlerts.evaluate(series(OPEN.plus(quarter), "15m", rsi("24"), rsi("25"))))
                .as("15 minutes into a 20-minute cooldown")
                .isZero();
        clock.advance(quarter);
        assertThat(indicatorAlerts.evaluate(series(OPEN.plus(quarter.multipliedBy(2)), "15m", rsi("23"), rsi("24"))))
                .isOne();

        assertThat(data.triggerCount(alert)).isEqualTo(2);
    }

    @Test
    void BR19_oncePerBar_firesOncePerCandle_andStaysActive() {
        UUID alert = indicator("SPOT", "EMA_CROSS", "4h", "CROSS_ABOVE", null, "ONCE_PER_BAR", null);
        Duration bar = Duration.ofHours(4);

        assertThat(indicatorAlerts.evaluate(series(OPEN, "4h", ema("101", "100"), ema("99", "100"))))
                .isOne();
        assertThat(indicatorAlerts.evaluate(series(OPEN, "4h", ema("101", "100"), ema("99", "100"))))
                .isZero();
        assertThat(indicatorAlerts.evaluate(series(OPEN.plus(bar), "4h", ema("102", "100"), ema("101", "100"))))
                .as("EMA20 stays above: no new cross")
                .isZero();
        assertThat(indicatorAlerts.evaluate(
                        series(OPEN.plus(bar.multipliedBy(2)), "4h", ema("101", "100"), ema("99.5", "100"))))
                .isOne();

        assertThat(data.triggerCount(alert)).isEqualTo(2);
        assertThat(data.status(alert)).isEqualTo("ACTIVE");
    }

    @Test
    void NSF06_onlyActiveUnexpiredAlertsOfTheCandlesSeries_areEvaluated() {
        UUID fires = indicator("SPOT", "RSI_14", "1h", "GREATER_THAN", "70", "ONCE", null);
        UUID otherTimeframe = indicator("SPOT", "RSI_14", "4h", "GREATER_THAN", "70", "ONCE", null);
        UUID otherMarket = indicator("FUTURES", "RSI_14", "1h", "GREATER_THAN", "70", "ONCE", null);
        UUID paused = data.indicatorAlert(
                trader, row, "SPOT", "RSI_14", "1h", "GREATER_THAN", "70", "ONCE", null, "PAUSED", null);
        UUID expired = data.indicatorAlert(
                trader, row, "SPOT", "RSI_14", "1h", "GREATER_THAN", "70", "ONCE", null, "ACTIVE", NOW);
        UUID price = data.priceAlert(trader, row, "SPOT", "GREATER_THAN", "1", "ONCE", null, "ACTIVE", null);

        assertThat(indicatorAlerts.evaluate(spot(OPEN, rsi("75"), rsi("72")))).isOne();

        assertThat(data.triggerCount(fires)).isOne();
        for (UUID untouched : List.of(otherTimeframe, otherMarket, paused, expired, price)) {
            assertThat(data.triggerCount(untouched)).as("alert %s", untouched).isZero();
        }
    }

    @Test
    void NSF06_aCandle_leavesFundingRateAndOpenInterestAlertsAlone() {
        UUID rsi = indicator("FUTURES", "RSI_14", "1h", "GREATER_THAN", "70", "ONCE", null);
        UUID funding = indicator("FUTURES", "FUNDING_RATE", "1h", "GREATER_THAN", "0", "ONCE", null);
        UUID openInterest = indicator("FUTURES", "OPEN_INTEREST_CHANGE", "1h", "GREATER_THAN", "0", "ONCE", null);

        int recorded = indicatorAlerts.evaluate(
                new IndicatorsComputed(pair, "X", MarketType.FUTURES, "1h", OPEN, rsi("75"), rsi("72")));

        assertThat(recorded).isOne();
        assertThat(data.triggerCount(rsi)).isOne();
        assertThat(data.triggerCount(funding)).isZero();
        assertThat(data.triggerCount(openInterest)).isZero();
    }

    @Test
    void Q39_aFundingRateAlertExpiringNow_doesNotFire_oneExpiringLaterDoes() {
        UUID expired = data.indicatorAlert(
                trader, row, "FUTURES", "FUNDING_RATE", "1h", "GREATER_THAN", "0.01", "ONCE", null, "ACTIVE", NOW);
        UUID later = data.indicatorAlert(
                trader,
                row,
                "FUTURES",
                "FUNDING_RATE",
                "1h",
                "GREATER_THAN",
                "0.01",
                "ONCE",
                null,
                "ACTIVE",
                NOW.plusSeconds(1));
        Instant hour = TriggerPolicy.barOpenTime(NOW);

        assertThat(fundingTrigger(expired, hour)).isFalse();
        assertThat(fundingTrigger(later, hour)).isTrue();

        assertThat(data.triggerCount(expired)).isZero();
        assertThat(recorder.of(expired)).isEmpty();
    }

    @Test
    void BR19_aFundingRateAlert_oncePerBar_firesOncePerHour_andEveryTime_waitsOnlyForItsCooldown() {
        UUID perBar = indicator("FUTURES", "FUNDING_RATE", "1h", "GREATER_THAN", "0.01", "ONCE_PER_BAR", null);
        UUID everyTime = indicator("FUTURES", "FUNDING_RATE", "1h", "GREATER_THAN", "0.01", "EVERY_TIME", 1);
        Instant hour = TriggerPolicy.barOpenTime(NOW);

        assertThat(fundingTrigger(perBar, hour)).isTrue();
        assertThat(fundingTrigger(everyTime, hour)).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(fundingTrigger(perBar, hour)).as("same 1h bar").isFalse();
        assertThat(fundingTrigger(everyTime, hour))
                .as("cooldown passed, same hour")
                .isTrue();
        clock.advance(Duration.ofSeconds(30));
        assertThat(fundingTrigger(everyTime, hour)).as("inside the cooldown").isFalse();
        clock.set(hour.plus(HOUR));
        assertThat(fundingTrigger(perBar, hour.plus(HOUR))).isTrue();

        assertThat(data.triggerCount(perBar)).isEqualTo(2);
        assertThat(data.triggerCount(everyTime)).isEqualTo(2);
        AlertTriggered event = recorder.of(perBar).getFirst();
        assertThat(event.indicator()).isEqualTo(AlertIndicator.FUNDING_RATE);
        assertThat(event.observedValue()).isEqualByComparingTo("0.012");
        assertThat(event.threshold()).isEqualByComparingTo("0.01");
    }

    @Test
    void D89_twoTriggersOfOneFundingRateReadingAtOnce_recordOne() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int race = 0; race < RACES; race++) {
                UUID alert = indicator("FUTURES", "FUNDING_RATE", "1h", "LESS_THAN", "0", "EVERY_TIME", 1);
                IndicatorAlertHit hit = fundingHit(alert, TriggerPolicy.barOpenTime(NOW));
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<Boolean>> both = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    both.add(pool.submit(() -> {
                        start.await();
                        return triggers.tryTriggerFundingRate(hit);
                    }));
                }

                assertThat(both.get(0).get() ^ both.get(1).get())
                        .as("race %d", race)
                        .isTrue();
                assertThat(data.triggerCount(alert)).as("race %d", race).isOne();
            }
        }
    }

    private boolean fundingTrigger(UUID alertId, Instant bar) {
        return triggers.tryTriggerFundingRate(fundingHit(alertId, bar));
    }

    private IndicatorAlertHit fundingHit(UUID alertId, Instant bar) {
        IndicatorAlert alert = triggers.activeFundingRateAlerts().stream()
                .filter(candidate -> candidate.alertId().equals(alertId))
                .findFirst()
                .orElseThrow();
        return new IndicatorAlertHit(alert, symbol, new BigDecimal("0.012"), bar, clock.instant());
    }

    private UUID indicator(
            String market,
            String indicator,
            String timeframe,
            String condition,
            String threshold,
            String triggerMode,
            Integer cooldownMinutes) {
        return data.indicatorAlert(
                trader,
                row,
                market,
                indicator,
                timeframe,
                condition,
                threshold,
                triggerMode,
                cooldownMinutes,
                "ACTIVE",
                null);
    }

    private Instant lastBar(UUID alertId) {
        return sql.sql("select last_bar_open_time from alert where alert_id = ?")
                .param(alertId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private IndicatorsComputed spot(Instant openTime, IndicatorLevels current, IndicatorLevels previous) {
        return series(openTime, "1h", current, previous);
    }

    private IndicatorsComputed series(
            Instant openTime, String timeframe, IndicatorLevels current, IndicatorLevels previous) {
        return new IndicatorsComputed(pair, symbol, MarketType.SPOT, timeframe, openTime, current, previous);
    }

    private static IndicatorLevels rsi(String value) {
        return new IndicatorLevels(new BigDecimal(value), null, null, null);
    }

    private static IndicatorLevels histogram(String value) {
        return new IndicatorLevels(null, new BigDecimal(value), null, null);
    }

    private static IndicatorLevels ema(String ema20, String ema50) {
        return new IndicatorLevels(null, null, new BigDecimal(ema20), new BigDecimal(ema50));
    }
}
