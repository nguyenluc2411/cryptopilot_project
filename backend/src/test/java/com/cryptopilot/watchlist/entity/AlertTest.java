package com.cryptopilot.watchlist.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.exception.IllegalAlertStateException;
import com.cryptopilot.watchlist.model.AlertDefinition;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AlertTest {

    private static final AlertDefinition PRICE_RULE = new AlertDefinition(
            MarketType.SPOT,
            AlertType.PRICE,
            null,
            null,
            ConditionOperator.CROSS_ABOVE,
            new BigDecimal("65000"),
            TriggerMode.ONCE,
            null,
            false,
            false,
            null);

    private static final AlertDefinition RSI_RULE = new AlertDefinition(
            MarketType.FUTURES,
            AlertType.INDICATOR,
            AlertIndicator.RSI_14,
            "4h",
            ConditionOperator.LESS_THAN,
            new BigDecimal("30"),
            TriggerMode.EVERY_TIME,
            5,
            true,
            true,
            Instant.parse("2026-11-01T00:00:00Z"));

    @Test
    void SRS342_aNewAlert_isActive_inTheApp_andHasNeverFired() {
        UUID user = UUID.randomUUID();
        UUID row = UUID.randomUUID();

        Alert alert = Alert.create(user, row, PRICE_RULE);

        assertThat(alert.getUserId()).isEqualTo(user);
        assertThat(alert.getWatchlistId()).isEqualTo(row);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(alert.isNotifyInApp()).isTrue();
        assertThat(alert.getTriggerCount()).isZero();
        assertThat(alert.getLastTriggeredAt()).isNull();
        assertThat(alert.getThreshold()).isEqualByComparingTo("65000");
        assertThat(alert.getIndicator()).isNull();
    }

    @Test
    void SRS343_pauseAndResume_moveBetweenActiveAndPaused() {
        Alert alert = Alert.create(UUID.randomUUID(), UUID.randomUUID(), PRICE_RULE);

        alert.pause();
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.PAUSED);
        alert.resume();
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACTIVE);
    }

    @Test
    void SRS343_pausingTwiceOrResumingAnActiveAlert_isAStateError() {
        Alert alert = Alert.create(UUID.randomUUID(), UUID.randomUUID(), PRICE_RULE);

        assertThatThrownBy(alert::resume).isInstanceOfSatisfying(IllegalAlertStateException.class, e -> {
            assertThat(e.status()).isEqualTo(AlertStatus.ACTIVE);
            assertThat(e.errorCode()).isEqualTo(ErrorCode.ALERT_STATUS_TRANSITION_INVALID);
        });
        alert.pause();
        assertThatThrownBy(alert::pause).isInstanceOf(IllegalAlertStateException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = AlertStatus.class,
            names = {"TRIGGERED", "EXPIRED"})
    void SRS343_anEngineStatus_cannotBePausedOrResumed_butAnEditBringsItBack(AlertStatus engineStatus)
            throws Exception {
        Alert alert = Alert.create(UUID.randomUUID(), UUID.randomUUID(), PRICE_RULE);
        set(alert, "status", engineStatus);

        assertThatThrownBy(alert::pause).isInstanceOf(IllegalAlertStateException.class);
        assertThatThrownBy(alert::resume).isInstanceOf(IllegalAlertStateException.class);

        alert.redefine(RSI_RULE);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.ACTIVE);
    }

    @Test
    void SRS343_anEditOfAPausedAlert_keepsItPaused_andReplacesTheWholeRule() {
        Alert alert = Alert.create(UUID.randomUUID(), UUID.randomUUID(), PRICE_RULE);
        alert.pause();

        alert.redefine(RSI_RULE);

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.PAUSED);
        assertThat(alert.getMarket()).isEqualTo(MarketType.FUTURES);
        assertThat(alert.getType()).isEqualTo(AlertType.INDICATOR);
        assertThat(alert.getIndicator()).isEqualTo(AlertIndicator.RSI_14);
        assertThat(alert.getTimeframe()).isEqualTo("4h");
        assertThat(alert.getCondition()).isEqualTo(ConditionOperator.LESS_THAN);
        assertThat(alert.getThreshold()).isEqualByComparingTo("30");
        assertThat(alert.getTriggerMode()).isEqualTo(TriggerMode.EVERY_TIME);
        assertThat(alert.getCooldownMinutes()).isEqualTo(5);
        assertThat(alert.isNotifyEmail()).isTrue();
        assertThat(alert.isNotifyPush()).isTrue();
        assertThat(alert.getExpiresAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    void BR20_anEdit_forgetsTheLastBarAndTheLastValue_butKeepsTheHistory() throws Exception {
        Alert alert = Alert.create(UUID.randomUUID(), UUID.randomUUID(), PRICE_RULE);
        Instant fired = Instant.parse("2026-10-01T10:00:00Z");
        set(alert, "lastBarOpenTime", fired);
        set(alert, "lastEvaluatedValue", new BigDecimal("64999"));
        set(alert, "triggerCount", 2);
        set(alert, "lastTriggeredAt", fired);

        alert.redefine(PRICE_RULE);

        assertThat(alert.getLastBarOpenTime()).isNull();
        assertThat(alert.getLastEvaluatedValue()).isNull();
        assertThat(alert.getTriggerCount()).isEqualTo(2);
        assertThat(alert.getLastTriggeredAt()).isEqualTo(fired);
    }

    @Test
    void SRS342_aRuleMixingPriceAndIndicatorFields_cannotBeBuilt() {
        assertThatThrownBy(() -> new AlertDefinition(
                        MarketType.SPOT,
                        AlertType.PRICE,
                        AlertIndicator.RSI_14,
                        "1h",
                        ConditionOperator.CROSS_ABOVE,
                        BigDecimal.ONE,
                        TriggerMode.ONCE,
                        null,
                        false,
                        false,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertDefinition(
                        MarketType.SPOT,
                        AlertType.INDICATOR,
                        AlertIndicator.RSI_14,
                        null,
                        ConditionOperator.CROSS_ABOVE,
                        BigDecimal.ONE,
                        TriggerMode.ONCE,
                        null,
                        false,
                        false,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // The engine fields and statuses are T-055's to write; a test sets them the way a loaded row would hold them.
    private static void set(Alert alert, String field, Object value) throws Exception {
        Field declared = Alert.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(alert, value);
    }
}
