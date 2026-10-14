package com.cryptopilot.watchlist.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.event.IndicatorLevels;
import com.cryptopilot.market.event.IndicatorsComputed;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.IndicatorAlertHit;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The evaluation of a closed candle: each alert of the series goes to its own evaluator, only a met condition is
 * offered to the database, with the candle's open time as the bar and the evaluation instant cut to microseconds; one
 * failing trigger leaves the others.
 *
 * <p>Rule: NSF-06, BR-18, BR-19, BR-20; SRS 3.4.4; D-89.
 */
class IndicatorAlertServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:00:05.123456789Z");
    private static final Instant OPEN = Instant.parse("2026-10-14T08:00:00Z");
    private static final UUID PAIR = UUID.randomUUID();

    private final AlertTriggerService triggers = mock(AlertTriggerService.class);
    private final IndicatorAlertServiceImpl service =
            new IndicatorAlertServiceImpl(triggers, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void NSF06_aMetCondition_isOfferedWithTheCandleAsTheBar_andTheValueThatMetIt() {
        IndicatorAlert rsi = alert(AlertIndicator.RSI_14, ConditionOperator.CROSS_BELOW, "30");
        when(triggers.activeIndicatorAlerts(PAIR, MarketType.SPOT, "1h")).thenReturn(List.of(rsi));
        when(triggers.tryTriggerIndicator(any())).thenReturn(true);

        int recorded = service.evaluate(candle(levels("29.5", null, null, null), levels("31", null, null, null)));

        ArgumentCaptor<IndicatorAlertHit> hit = ArgumentCaptor.forClass(IndicatorAlertHit.class);
        verify(triggers).tryTriggerIndicator(hit.capture());
        assertThat(recorded).isOne();
        assertThat(hit.getValue().alert()).isEqualTo(rsi);
        assertThat(hit.getValue().symbol()).isEqualTo("BTCUSDT");
        assertThat(hit.getValue().observedValue()).isEqualByComparingTo("29.5");
        assertThat(hit.getValue().barOpenTime()).isEqualTo(OPEN);
        assertThat(hit.getValue().at()).isEqualTo(Instant.parse("2026-10-14T09:00:05.123456Z"));
    }

    @Test
    void BR20_eachIndicator_isReadByItsOwnEvaluator() {
        IndicatorAlert rsi = alert(AlertIndicator.RSI_14, ConditionOperator.GREATER_THAN, "70");
        IndicatorAlert macd = alert(AlertIndicator.MACD_CROSS, ConditionOperator.CROSS_ABOVE, null);
        IndicatorAlert ema = alert(AlertIndicator.EMA_CROSS, ConditionOperator.CROSS_BELOW, null);
        when(triggers.activeIndicatorAlerts(PAIR, MarketType.SPOT, "1h")).thenReturn(List.of(rsi, macd, ema));
        when(triggers.tryTriggerIndicator(any())).thenReturn(true);

        // RSI 72 holds, the histogram crosses above zero, EMA20 stays above EMA50.
        int recorded = service.evaluate(candle(levels("72", "0.3", "101", "100"), levels("68", "-0.1", "102", "100")));

        ArgumentCaptor<IndicatorAlertHit> hits = ArgumentCaptor.forClass(IndicatorAlertHit.class);
        verify(triggers, times(2)).tryTriggerIndicator(hits.capture());
        assertThat(recorded).isEqualTo(2);
        assertThat(hits.getAllValues())
                .extracting(hit -> hit.alert().indicator(), hit -> hit.observedValue()
                        .toPlainString())
                .containsExactly(tuple(AlertIndicator.RSI_14, "72"), tuple(AlertIndicator.MACD_CROSS, "0.3"));
    }

    @Test
    void BR20_aConditionNotMet_orACandleWithoutData_offersNothing() {
        IndicatorAlert rsi = alert(AlertIndicator.RSI_14, ConditionOperator.CROSS_BELOW, "30");
        IndicatorAlert ema = alert(AlertIndicator.EMA_CROSS, ConditionOperator.CROSS_ABOVE, null);
        when(triggers.activeIndicatorAlerts(PAIR, MarketType.SPOT, "1h")).thenReturn(List.of(rsi, ema));

        int stays = service.evaluate(candle(levels("25", null, null, null), levels("28", null, null, null)));
        int warmingUp = service.evaluate(candle(IndicatorLevels.NONE, IndicatorLevels.NONE));

        assertThat(stays).isZero();
        assertThat(warmingUp).isZero();
        verify(triggers, never()).tryTriggerIndicator(any());
    }

    @Test
    void NSF06_anIndicatorWithoutAnEvaluator_isSkipped() {
        IndicatorAlert funding = alert(AlertIndicator.FUNDING_RATE, ConditionOperator.GREATER_THAN, "0");
        when(triggers.activeIndicatorAlerts(PAIR, MarketType.SPOT, "1h")).thenReturn(List.of(funding));

        assertThat(service.evaluate(candle(levels("50", "1", "2", "1"), IndicatorLevels.NONE)))
                .isZero();
        verify(triggers, never()).tryTriggerIndicator(any());
    }

    @Test
    void D89_aRefusedOrFailingTrigger_isNotCounted_andTheNextAlertIsStillOffered() {
        IndicatorAlert failing = alert(AlertIndicator.RSI_14, ConditionOperator.GREATER_THAN, "10");
        IndicatorAlert refused = alert(AlertIndicator.RSI_14, ConditionOperator.GREATER_THAN, "20");
        IndicatorAlert recorded = alert(AlertIndicator.RSI_14, ConditionOperator.GREATER_THAN, "30");
        when(triggers.activeIndicatorAlerts(PAIR, MarketType.SPOT, "1h"))
                .thenReturn(List.of(failing, refused, recorded));
        when(triggers.tryTriggerIndicator(any())).thenAnswer(call -> {
            IndicatorAlert alert = call.<IndicatorAlertHit>getArgument(0).alert();
            if (alert == failing) {
                throw new IllegalStateException("database down");
            }
            return alert == recorded;
        });

        assertThat(service.evaluate(candle(levels("50", null, null, null), IndicatorLevels.NONE)))
                .isOne();
        verify(triggers, times(3)).tryTriggerIndicator(any());
    }

    private static IndicatorsComputed candle(IndicatorLevels current, IndicatorLevels previous) {
        return new IndicatorsComputed(PAIR, "BTCUSDT", MarketType.SPOT, "1h", OPEN, current, previous);
    }

    private static IndicatorLevels levels(String rsi, String histogram, String ema20, String ema50) {
        return new IndicatorLevels(decimal(rsi), decimal(histogram), decimal(ema20), decimal(ema50));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static IndicatorAlert alert(AlertIndicator indicator, ConditionOperator condition, String threshold) {
        return new IndicatorAlert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                MarketType.SPOT,
                indicator,
                "1h",
                condition,
                decimal(threshold),
                TriggerMode.EVERY_TIME,
                1,
                null,
                true,
                false,
                false,
                0,
                0);
    }
}
