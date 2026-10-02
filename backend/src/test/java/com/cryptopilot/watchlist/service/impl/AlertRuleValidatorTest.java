package com.cryptopilot.watchlist.service.impl;

import static com.cryptopilot.market.model.enums.MarketType.FUTURES;
import static com.cryptopilot.market.model.enums.MarketType.SPOT;
import static com.cryptopilot.watchlist.model.enums.AlertIndicator.FUNDING_RATE;
import static com.cryptopilot.watchlist.model.enums.AlertIndicator.OPEN_INTEREST_CHANGE;
import static com.cryptopilot.watchlist.model.enums.AlertIndicator.RSI_14;
import static com.cryptopilot.watchlist.model.enums.AlertType.INDICATOR;
import static com.cryptopilot.watchlist.model.enums.AlertType.PRICE;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.CROSS_ABOVE;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.CROSS_BELOW;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.GREATER_THAN;
import static com.cryptopilot.watchlist.model.enums.ConditionOperator.LESS_THAN;
import static com.cryptopilot.watchlist.model.enums.TriggerMode.EVERY_TIME;
import static com.cryptopilot.watchlist.model.enums.TriggerMode.ONCE;
import static com.cryptopilot.watchlist.model.enums.TriggerMode.ONCE_PER_BAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.AlertDefinition;
import com.cryptopilot.watchlist.model.AlertRuleInput;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AlertRuleValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");

    private static final PairFilters FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));

    // ------------------------------------------------------------------------------------------ PRICE

    @Test
    void SRS342_aPriceAlert_keepsItsTargetAndNamesNoIndicatorOrTimeframe() {
        AlertDefinition rule = AlertRuleValidator.validate(price(SPOT, "65000.5", ONCE, null, null), NOW);

        assertThat(rule.type()).isEqualTo(PRICE);
        assertThat(rule.indicator()).isNull();
        assertThat(rule.timeframe()).isNull();
        assertThat(rule.threshold()).isEqualByComparingTo("65000.5");
        assertThat(rule.notifyEmail()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void SRS342_aTargetNotAboveZero_isOutOfRange(String target) {
        assertErrors(price(SPOT, target, ONCE, null, null), Map.of("threshold", "MSG15"));
    }

    @Test
    void SRS342_aPriceAlertWithoutATarget_isRefused() {
        assertErrors(price(SPOT, null, ONCE, null, null), Map.of("threshold", "MSG01"));
    }

    @Test
    void SRS342_aPriceAlertNamingAnIndicatorOrTimeframe_isRefused() {
        AlertRuleInput input = new AlertRuleInput(
                SPOT, PRICE, RSI_14, "1h", CROSS_ABOVE, BigDecimal.TEN, ONCE, null, false, false, null);

        assertErrors(input, Map.of("indicator", "MSG01", "timeframe", "MSG01"));
    }

    @Test
    void BR30_aTarget_isRoundedToTheTickHalfUp() {
        AlertDefinition rule = AlertRuleValidator.validate(price(SPOT, "100.005", ONCE, null, null), NOW);

        assertThat(AlertRuleValidator.onTick(rule, FILTERS).threshold()).isEqualByComparingTo("100.01");
    }

    @Test
    void BR30_aTargetThatRoundsToZero_isOutOfRange() {
        AlertDefinition rule = AlertRuleValidator.validate(price(SPOT, "0.004", ONCE, null, null), NOW);

        assertThatThrownBy(() -> AlertRuleValidator.onTick(rule, FILTERS))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                        .containsExactlyEntriesOf(Map.of("threshold", "MSG15")));
    }

    @Test
    void BR30_anIndicatorThreshold_isNotPutOnTheTick() {
        AlertDefinition rule = AlertRuleValidator.validate(indicator(SPOT, RSI_14, "1h", GREATER_THAN, "70.005"), NOW);

        assertThat(AlertRuleValidator.onTick(rule, FILTERS)).isSameAs(rule);
    }

    // ------------------------------------------------------------------------------------------ indicators

    @ParameterizedTest
    @ValueSource(strings = {"15m", "1h", "4h", "1d"})
    void SRS342_rsi_takesEachTimeframe(String timeframe) {
        AlertDefinition rule = AlertRuleValidator.validate(indicator(SPOT, RSI_14, timeframe, LESS_THAN, "30"), NOW);

        assertThat(rule.indicator()).isEqualTo(RSI_14);
        assertThat(rule.timeframe()).isEqualTo(timeframe);
        assertThat(rule.threshold()).isEqualByComparingTo("30");
    }

    @Test
    void SRS342_rsiLevels_runFromZeroToAHundred() {
        AlertRuleValidator.validate(indicator(SPOT, RSI_14, "1h", GREATER_THAN, "0"), NOW);
        AlertRuleValidator.validate(indicator(SPOT, RSI_14, "1h", GREATER_THAN, "100"), NOW);

        assertErrors(indicator(SPOT, RSI_14, "1h", GREATER_THAN, "100.01"), Map.of("threshold", "MSG15"));
        assertErrors(indicator(SPOT, RSI_14, "1h", GREATER_THAN, "-0.01"), Map.of("threshold", "MSG15"));
        assertErrors(indicator(SPOT, RSI_14, "1h", GREATER_THAN, null), Map.of("threshold", "MSG01"));
    }

    @Test
    void SRS342_aTimeframeOutsideTheFour_orNone_isRefused() {
        assertErrors(indicator(SPOT, RSI_14, "2h", GREATER_THAN, "70"), Map.of("timeframe", "MSG01"));
        assertErrors(indicator(SPOT, RSI_14, null, GREATER_THAN, "70"), Map.of("timeframe", "MSG01"));
    }

    @Test
    void SRS342_anIndicatorAlertWithoutAnIndicator_isRefused() {
        assertErrors(indicator(SPOT, null, "1h", GREATER_THAN, "70"), Map.of("indicator", "MSG01"));
    }

    @ParameterizedTest
    @EnumSource(
            value = AlertIndicator.class,
            names = {"MACD_CROSS", "EMA_CROSS"})
    void TD79_aLineCross_crossesZero_inEitherDirection(AlertIndicator line) {
        AlertDefinition up = AlertRuleValidator.validate(indicator(FUTURES, line, "4h", CROSS_ABOVE, null), NOW);
        AlertDefinition down = AlertRuleValidator.validate(indicator(SPOT, line, "15m", CROSS_BELOW, null), NOW);

        assertThat(up.threshold()).isEqualByComparingTo("0");
        assertThat(down.threshold()).isEqualByComparingTo("0");
        assertThat(up.timeframe()).isEqualTo("4h");
    }

    @ParameterizedTest
    @EnumSource(
            value = AlertIndicator.class,
            names = {"MACD_CROSS", "EMA_CROSS"})
    void TD79_aLineCross_takesNoThresholdAndOnlyACrossCondition(AlertIndicator line) {
        assertErrors(
                indicator(SPOT, line, "1h", GREATER_THAN, "1"), Map.of("condition", "MSG01", "threshold", "MSG01"));
    }

    @ParameterizedTest
    @EnumSource(
            value = AlertIndicator.class,
            names = {"FUNDING_RATE", "OPEN_INTEREST_CHANGE"})
    void A40_aFuturesIndicator_getsTheOneHourTimeframeFromTheServer(AlertIndicator futures) {
        AlertDefinition rule = AlertRuleValidator.validate(indicator(FUTURES, futures, null, LESS_THAN, "-0.05"), NOW);

        assertThat(rule.timeframe()).isEqualTo("1h");
        assertThat(rule.threshold()).isEqualByComparingTo("-0.05");
    }

    @ParameterizedTest
    @EnumSource(
            value = AlertIndicator.class,
            names = {"FUNDING_RATE", "OPEN_INTEREST_CHANGE"})
    void A40_aTimeframeSentForAFuturesIndicator_isRefused(AlertIndicator futures) {
        assertErrors(indicator(FUTURES, futures, "1h", GREATER_THAN, "1"), Map.of("timeframe", "MSG01"));
    }

    @ParameterizedTest
    @EnumSource(
            value = AlertIndicator.class,
            names = {"FUNDING_RATE", "OPEN_INTEREST_CHANGE"})
    void SRS342_aFuturesIndicatorOnSpot_isRefused(AlertIndicator futures) {
        assertErrors(indicator(SPOT, futures, null, GREATER_THAN, "1"), Map.of("market", "MSG01"));
    }

    @Test
    void SRS342_aFuturesIndicatorWithoutAThreshold_isRefused() {
        assertErrors(indicator(FUTURES, OPEN_INTEREST_CHANGE, null, GREATER_THAN, null), Map.of("threshold", "MSG01"));
    }

    // ------------------------------------------------------------------------------------------ mode, cooldown, expiry

    @Test
    void BR19_everyTime_needsACooldown() {
        assertErrors(price(SPOT, "1", EVERY_TIME, null, null), Map.of("cooldownMinutes", "MSG01"));
        assertThat(AlertRuleValidator.validate(price(SPOT, "1", EVERY_TIME, 1, null), NOW)
                        .cooldownMinutes())
                .isEqualTo(1);
    }

    @Test
    void BR19_aCooldownBelowOneMinute_isOutOfRange() {
        assertErrors(price(SPOT, "1", ONCE_PER_BAR, 0, null), Map.of("cooldownMinutes", "MSG15"));
    }

    @Test
    void BR19_onceAndOncePerBar_mayGoWithoutACooldown() {
        assertThat(AlertRuleValidator.validate(price(SPOT, "1", ONCE, null, null), NOW)
                        .cooldownMinutes())
                .isNull();
        assertThat(AlertRuleValidator.validate(price(SPOT, "1", ONCE_PER_BAR, null, null), NOW)
                        .triggerMode())
                .isEqualTo(ONCE_PER_BAR);
    }

    @Test
    void SRS342_anExpiry_isAfterNowAndAtMostNinetyDaysAhead() {
        Instant edge = NOW.plus(Duration.ofDays(90));
        assertThat(AlertRuleValidator.validate(price(SPOT, "1", ONCE, null, edge), NOW)
                        .expiresAt())
                .isEqualTo(edge);

        assertErrors(price(SPOT, "1", ONCE, null, edge.plusSeconds(1)), Map.of("expiresAt", "MSG15"));
        assertErrors(price(SPOT, "1", ONCE, null, NOW), Map.of("expiresAt", "MSG15"));
    }

    @Test
    void SRS343_aResumeAfterTheExpiry_isRefused() {
        AlertRuleValidator.requireNotExpired(null, NOW);
        AlertRuleValidator.requireNotExpired(NOW.plusSeconds(1), NOW);

        assertThatThrownBy(() -> AlertRuleValidator.requireNotExpired(NOW, NOW))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                        .containsExactlyEntriesOf(Map.of("expiresAt", "MSG15")));
    }

    @Test
    void SRS342_everyBrokenRule_isReportedInOneResponse() {
        AlertRuleInput input = new AlertRuleInput(
                SPOT,
                INDICATOR,
                FUNDING_RATE,
                "4h",
                GREATER_THAN,
                null,
                EVERY_TIME,
                null,
                true,
                true,
                NOW.minusSeconds(1));

        assertErrors(
                input,
                Map.of(
                        "market", "MSG01",
                        "timeframe", "MSG01",
                        "threshold", "MSG01",
                        "cooldownMinutes", "MSG01",
                        "expiresAt", "MSG15"));
    }

    @Test
    void SRS342_theChannels_passThrough() {
        AlertRuleInput input =
                new AlertRuleInput(SPOT, PRICE, null, null, CROSS_ABOVE, BigDecimal.ONE, ONCE, null, true, true, null);

        AlertDefinition rule = AlertRuleValidator.validate(input, NOW);

        assertThat(rule.notifyEmail()).isTrue();
        assertThat(rule.notifyPush()).isTrue();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static AlertRuleInput price(
            MarketType market, String target, TriggerMode mode, Integer cooldown, Instant expiresAt) {
        return new AlertRuleInput(
                market,
                AlertType.PRICE,
                null,
                null,
                ConditionOperator.CROSS_ABOVE,
                target == null ? null : new BigDecimal(target),
                mode,
                cooldown,
                false,
                false,
                expiresAt);
    }

    private static AlertRuleInput indicator(
            MarketType market, AlertIndicator indicator, String timeframe, ConditionOperator condition, String value) {
        return new AlertRuleInput(
                market,
                AlertType.INDICATOR,
                indicator,
                timeframe,
                condition,
                value == null ? null : new BigDecimal(value),
                ONCE,
                null,
                false,
                false,
                null);
    }

    private static void assertErrors(AlertRuleInput input, Map<String, String> expected) {
        assertThatThrownBy(() -> AlertRuleValidator.validate(input, NOW))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                        .containsExactlyInAnyOrderEntriesOf(expected));
    }
}
