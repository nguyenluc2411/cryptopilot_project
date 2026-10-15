package com.cryptopilot.watchlist.calculator;

import static com.cryptopilot.watchlist.model.enums.ConditionOperator.CROSS_ABOVE;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.CROSS_BELOW;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.GREATER_THAN;
import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.OpenInterestSample;
import com.cryptopilot.watchlist.model.IndicatorReading;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The open interest change over a rolling hour, from stored readings alone: the formula and its scale, the base and
 * previous readings within their tolerance, a zero base, and the cross it feeds.
 *
 * <p>Rule: NSF-06, BR-20; SRS 3.4.2; Q-45.
 */
class OpenInterestChangeEvaluatorTest {

    private static final Instant T = Instant.parse("2026-10-14T10:05:00Z");
    private static final Duration STEP = Duration.ofMinutes(5);
    private static final BigDecimal LEVEL = new BigDecimal("10");

    private final NavigableMap<Instant, BigDecimal> stored = new TreeMap<>();

    @Test
    void Q45_theChange_isInPercentOfTheBaseAnHourEarlier_atTwelvePlaces() {
        store(T.minus(Duration.ofHours(1)), "3000");
        store(T, "3100");

        IndicatorReading reading = read(T).orElseThrow();

        assertThat(reading.current()).isEqualTo(new BigDecimal("3.333333333333"));
        assertThat(reading.previous()).isNull();
    }

    @Test
    void Q45_aLevelOfTen_meansTenPercent() {
        store(T.minus(Duration.ofHours(1)), "1000");
        store(T, "1100");

        assertThat(IndicatorCondition.met(GREATER_THAN, new BigDecimal("9.99"), read(T).orElseThrow()))
                .isTrue();
        assertThat(IndicatorCondition.met(GREATER_THAN, LEVEL, read(T).orElseThrow()))
                .as("exactly 10 %: strict")
                .isFalse();
    }

    @Test
    void BR20_anIncreaseCrossingTheLevel_fires_andReachingItExactlyIsEnough() {
        series("1000", "1000", "1050", "1100");

        IndicatorReading reading = read(T).orElseThrow();

        assertThat(reading.previous()).isEqualByComparingTo("5");
        assertThat(reading.current()).isEqualByComparingTo("10");
        assertThat(IndicatorCondition.met(CROSS_ABOVE, LEVEL, reading)).isTrue();
    }

    @Test
    void BR20_aChangeStayingAboveOrBelow_doesNotCross() {
        series("1000", "1000", "1150", "1200");
        assertThat(IndicatorCondition.met(CROSS_ABOVE, LEVEL, read(T).orElseThrow()))
                .as("15 % then 20 %")
                .isFalse();

        stored.clear();
        series("1000", "1000", "1020", "1050");
        assertThat(IndicatorCondition.met(CROSS_ABOVE, LEVEL, read(T).orElseThrow()))
                .as("2 % then 5 %")
                .isFalse();
        assertThat(IndicatorCondition.met(CROSS_BELOW, LEVEL.negate(), read(T).orElseThrow()))
                .isFalse();
    }

    @Test
    void Q45_aBaseAtTheEdgeOfTheTolerance_isUsed_oneJustOutsideIsNot() {
        Instant edge = T.minus(Duration.ofHours(1)).minus(OpenInterestChangeEvaluator.TOLERANCE);
        store(edge, "1000");
        store(T, "1100");
        assertThat(read(T)).isPresent();

        stored.clear();
        store(edge.minusNanos(1_000), "1000");
        store(T, "1100");
        assertThat(read(T))
                .as("no base within the tolerance: no value, no error")
                .isEmpty();
    }

    @Test
    void Q45_theBase_isTheReadingClosestToAnHourEarlier_atOrBeforeIt() {
        store(T.minus(Duration.ofMinutes(65)), "500");
        store(T.minus(Duration.ofMinutes(60)), "1000");
        store(T.minus(Duration.ofMinutes(55)), "2000");
        store(T, "1100");

        assertThat(read(T).orElseThrow().current()).isEqualByComparingTo("10");
    }

    @Test
    void Q45_aZeroBase_givesNoValue() {
        store(T.minus(Duration.ofHours(1)), "0");
        store(T, "1100");

        assertThat(read(T)).isEmpty();
    }

    @Test
    void Q45_withoutTheReadingItself_thereIsNoValue() {
        store(T.minus(Duration.ofMinutes(65)), "1000");
        store(T.minus(Duration.ofHours(1)), "1000");
        store(T.minus(STEP), "1100");

        assertThat(read(T)).as("the latest reading is not the announced one").isEmpty();
    }

    @Test
    void BR20_aGapBeforeTheReading_leavesNoPreviousValue_soACrossDoesNotFire() {
        store(T.minus(Duration.ofMinutes(75)), "1000");
        store(T.minus(Duration.ofMinutes(60)), "1000");
        store(T.minus(Duration.ofMinutes(15)), "1000");
        store(T, "1100");

        IndicatorReading reading = read(T).orElseThrow();

        assertThat(reading.previous())
                .as("the reading before is 15 minutes old")
                .isNull();
        assertThat(IndicatorCondition.met(CROSS_ABOVE, LEVEL, reading)).isFalse();
        assertThat(IndicatorCondition.met(GREATER_THAN, new BigDecimal("9"), reading))
                .as("a level still holds")
                .isTrue();
    }

    @Test
    void BR20_aPreviousReadingWithoutItsOwnBase_leavesNoPreviousValue() {
        store(T.minus(Duration.ofHours(1)), "1000");
        store(T.minus(STEP), "1050");
        store(T, "1100");

        assertThat(read(T).orElseThrow().previous()).isNull();
    }

    /** Readings every five minutes from T − 65 min: the last four as given, every earlier one equal to the first. */
    private void series(String... lastFour) {
        for (int i = 13; i >= 0; i--) {
            String value = i >= lastFour.length ? lastFour[0] : lastFour[lastFour.length - 1 - i];
            store(T.minus(STEP.multipliedBy(i)), value);
        }
    }

    private void store(Instant at, String openInterest) {
        stored.put(at, new BigDecimal(openInterest));
    }

    private Optional<IndicatorReading> read(Instant sampleTime) {
        return OpenInterestChangeEvaluator.read(sampleTime, at -> Optional.ofNullable(stored.floorEntry(at))
                .map((Map.Entry<Instant, BigDecimal> entry) ->
                        new OpenInterestSample(entry.getKey(), entry.getValue())));
    }
}
