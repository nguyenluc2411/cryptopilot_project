package com.cryptopilot.watchlist.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.watchlist.model.IndicatorReading;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The FUNDING_RATE unit (a percent level against the exchange's fraction, exactly) and the previous rate a cross is
 * measured from.
 *
 * <p>Rule: NSF-06, BR-20; SRS 3.4.4; Q-30.
 */
class FundingRateEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");
    private static final Duration STALENESS = Duration.ofMinutes(5);

    private final UUID pair = UUID.randomUUID();
    private final FundingRateEvaluator rates = new FundingRateEvaluator(STALENESS);

    @ParameterizedTest(name = "{0} % = {1}")
    @CsvSource({"0.01, 0.0001", "-0.0375, -0.000375", "0.75, 0.0075", "0, 0", "0.00001234, 0.0000001234"})
    void Q30_aPercentLevel_isDividedByAHundredExactly(String percent, String fraction) {
        assertThat(FundingRateEvaluator.level(new BigDecimal(percent))).isEqualByComparingTo(fraction);
        assertThat(FundingRateEvaluator.percent(new BigDecimal(fraction))).isEqualByComparingTo(percent);
    }

    @Test
    void Q30_theExchangesFullScale_isKept() {
        BigDecimal rate = new BigDecimal("0.00012345");

        assertThat(FundingRateEvaluator.percent(rate)).isEqualTo(new BigDecimal("0.012345"));
    }

    @Test
    void BR20_aRateJustAboveThePercentLevel_isGreater_andTheLevelItselfIsNot() {
        BigDecimal level = FundingRateEvaluator.level(new BigDecimal("0.01"));

        assertThat(IndicatorCondition.met(
                        ConditionOperator.GREATER_THAN, level, new IndicatorReading(null, new BigDecimal("0.0001"))))
                .isFalse();
        assertThat(IndicatorCondition.met(
                        ConditionOperator.GREATER_THAN,
                        level,
                        new IndicatorReading(null, new BigDecimal("0.00010001"))))
                .isTrue();
    }

    @Test
    void BR20_theFirstReadingOfAPair_hasNoPreviousValue_theNextHasIt() {
        IndicatorReading first = rates.read(pair, new BigDecimal("0.0001"), NOW);
        IndicatorReading second = rates.read(pair, new BigDecimal("0.0002"), NOW.plusSeconds(1));

        assertThat(first.previous()).isNull();
        assertThat(first.current()).isEqualByComparingTo("0.0001");
        assertThat(second.previous()).isEqualByComparingTo("0.0001");
        assertThat(second.current()).isEqualByComparingTo("0.0002");
    }

    @Test
    void D87_aPreviousRateOlderThanTheStaleness_isNotUsed_exactlyTheStalenessIs() {
        rates.read(pair, new BigDecimal("0.0001"), NOW);
        IndicatorReading atTheLimit = rates.read(pair, new BigDecimal("0.0002"), NOW.plus(STALENESS));
        IndicatorReading pastIt = rates.read(
                pair,
                new BigDecimal("0.0003"),
                NOW.plus(STALENESS.multipliedBy(2)).plusNanos(1_000));

        assertThat(atTheLimit.previous()).isEqualByComparingTo("0.0001");
        assertThat(pastIt.previous()).isNull();
    }

    @Test
    void NSF06_aForgottenOrNoLongerWatchedPair_startsAgainWithoutAPreviousValue() {
        UUID other = UUID.randomUUID();
        rates.read(pair, new BigDecimal("0.0001"), NOW);
        rates.read(other, new BigDecimal("0.0001"), NOW);

        rates.forget(pair);
        rates.retain(Set.of(pair));

        assertThat(rates.read(pair, new BigDecimal("0.0002"), NOW.plusSeconds(1))
                        .previous())
                .isNull();
        assertThat(rates.read(other, new BigDecimal("0.0002"), NOW.plusSeconds(1))
                        .previous())
                .isNull();
    }
}
