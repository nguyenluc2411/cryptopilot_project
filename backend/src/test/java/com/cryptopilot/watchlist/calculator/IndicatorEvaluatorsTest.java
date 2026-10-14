package com.cryptopilot.watchlist.calculator;

import static com.cryptopilot.watchlist.model.enums.ConditionOperator.CROSS_ABOVE;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.CROSS_BELOW;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.LESS_THAN;
import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.event.IndicatorLevels;
import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Each evaluator reads its own value from a closed candle's stored indicators, and with {@link IndicatorCondition}
 * fires on a cross, not while the value stays on one side, at the level exactly, and never without data.
 *
 * <p>Rule: NSF-06, BR-18, BR-20; SRS 3.4.2, 3.4.4; TECHNICAL_DESIGN 7.9.
 */
class IndicatorEvaluatorsTest {

    private static final BigDecimal RSI_LEVEL = new BigDecimal("30");

    @Nested
    class Rsi14 {

        private final Rsi14Evaluator evaluator = new Rsi14Evaluator();

        @Test
        void SRS342_namesRsi14_andReadsTheRsiOfBothCandles() {
            IndicatorReading reading =
                    evaluator.read(candle(rsi("29.5"), rsi("31"))).orElseThrow();

            assertThat(evaluator.indicator()).isEqualTo(AlertIndicator.RSI_14);
            assertThat(reading.current()).isEqualByComparingTo("29.5");
            assertThat(reading.previous()).isEqualByComparingTo("31");
        }

        @Test
        void BR20_firesOnACrossBelow_andAtTheLevelExactly() {
            assertThat(met(evaluator, CROSS_BELOW, RSI_LEVEL, rsi("29.5"), rsi("31")))
                    .isTrue();
            assertThat(met(evaluator, CROSS_BELOW, RSI_LEVEL, rsi("30"), rsi("31")))
                    .isTrue();
        }

        @Test
        void BR20_doesNotFireWhileTheRsiStaysOnOneSide() {
            assertThat(met(evaluator, CROSS_BELOW, RSI_LEVEL, rsi("25"), rsi("28")))
                    .isFalse();
            assertThat(met(evaluator, CROSS_BELOW, RSI_LEVEL, rsi("35"), rsi("31")))
                    .isFalse();
        }

        @Test
        void BR18_aCandleStillWarmingUp_givesNoReading() {
            assertThat(evaluator.read(candle(IndicatorLevels.NONE, rsi("31")))).isEmpty();
        }

        @Test
        void BR20_withoutThePreviousCandle_aCrossDoesNotFire_butALevelDoes() {
            assertThat(met(evaluator, CROSS_BELOW, RSI_LEVEL, rsi("29"), IndicatorLevels.NONE))
                    .isFalse();
            assertThat(met(evaluator, LESS_THAN, RSI_LEVEL, rsi("29"), IndicatorLevels.NONE))
                    .isTrue();
        }
    }

    @Nested
    class MacdCross {

        private final MacdCrossEvaluator evaluator = new MacdCrossEvaluator();

        @Test
        void TD79_readsTheHistogram_whichIsTheMacdLineMinusItsSignal() {
            IndicatorReading reading =
                    evaluator.read(candle(histogram("0.4"), histogram("-0.2"))).orElseThrow();

            assertThat(evaluator.indicator()).isEqualTo(AlertIndicator.MACD_CROSS);
            assertThat(reading.current()).isEqualByComparingTo("0.4");
            assertThat(reading.previous()).isEqualByComparingTo("-0.2");
        }

        @Test
        void BR20_firesWhenTheLineCrossesTheSignal_inTheDirectionAsked() {
            assertThat(met(evaluator, CROSS_ABOVE, null, histogram("0.4"), histogram("-0.2")))
                    .isTrue();
            assertThat(met(evaluator, CROSS_ABOVE, null, histogram("0"), histogram("-0.2")))
                    .as("the line meets the signal")
                    .isTrue();
            assertThat(met(evaluator, CROSS_BELOW, null, histogram("0.4"), histogram("-0.2")))
                    .isFalse();
        }

        @Test
        void BR20_doesNotFireWhileTheLineStaysAboveOrBelowTheSignal() {
            assertThat(met(evaluator, CROSS_ABOVE, null, histogram("0.4"), histogram("0.1")))
                    .isFalse();
            assertThat(met(evaluator, CROSS_BELOW, null, histogram("-0.4"), histogram("-0.1")))
                    .isFalse();
        }

        @Test
        void BR18_noHistogramYet_givesNoReading() {
            assertThat(evaluator.read(candle(rsi("50"), histogram("0.1")))).isEmpty();
        }
    }

    @Nested
    class EmaCross {

        private final EmaCrossEvaluator evaluator = new EmaCrossEvaluator();

        @Test
        void TD79_readsEma20MinusEma50_onBothCandles() {
            IndicatorReading reading = evaluator
                    .read(candle(ema("101.5", "100"), ema("99", "100.25")))
                    .orElseThrow();

            assertThat(evaluator.indicator()).isEqualTo(AlertIndicator.EMA_CROSS);
            assertThat(reading.current()).isEqualByComparingTo("1.5");
            assertThat(reading.previous()).isEqualByComparingTo("-1.25");
        }

        @Test
        void BR20_firesWhenEma20CrossesEma50_andWhenTheyMeet() {
            assertThat(met(evaluator, CROSS_ABOVE, null, ema("101", "100"), ema("99", "100")))
                    .isTrue();
            assertThat(met(evaluator, CROSS_BELOW, null, ema("100", "100"), ema("101", "100")))
                    .isTrue();
        }

        @Test
        void BR20_doesNotFireWhileEma20StaysOnOneSide() {
            assertThat(met(evaluator, CROSS_ABOVE, null, ema("102", "100"), ema("101", "100")))
                    .isFalse();
            assertThat(met(evaluator, CROSS_BELOW, null, ema("98", "100"), ema("99", "100")))
                    .isFalse();
        }

        @Test
        void BR18_ema50StillWarmingUp_givesNoReading_andNoPreviousSpreadWithoutBoth() {
            assertThat(evaluator.read(candle(ema("101", null), ema("99", "100"))))
                    .isEmpty();
            assertThat(evaluator
                            .read(candle(ema("101", "100"), ema("99", null)))
                            .orElseThrow()
                            .previous())
                    .isNull();
        }
    }

    private static boolean met(
            IndicatorEvaluator evaluator,
            ConditionOperator condition,
            BigDecimal threshold,
            IndicatorLevels current,
            IndicatorLevels previous) {
        return evaluator
                .read(candle(current, previous))
                .map(reading -> IndicatorCondition.met(condition, threshold, reading))
                .orElse(false);
    }

    private static IndicatorsComputed candle(IndicatorLevels current, IndicatorLevels previous) {
        return new IndicatorsComputed(
                UUID.randomUUID(),
                "BTCUSDT",
                MarketType.SPOT,
                "1h",
                Instant.parse("2026-10-14T08:00:00Z"),
                current,
                previous);
    }

    private static IndicatorLevels rsi(String value) {
        return new IndicatorLevels(new BigDecimal(value), null, null, null);
    }

    private static IndicatorLevels histogram(String value) {
        return new IndicatorLevels(null, new BigDecimal(value), null, null);
    }

    private static IndicatorLevels ema(String ema20, String ema50) {
        return new IndicatorLevels(
                null, null, ema20 == null ? null : new BigDecimal(ema20), ema50 == null ? null : new BigDecimal(ema50));
    }
}
