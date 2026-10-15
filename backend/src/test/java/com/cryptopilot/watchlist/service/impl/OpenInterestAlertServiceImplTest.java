package com.cryptopilot.watchlist.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.OpenInterestSample;
import com.cryptopilot.market.event.OpenInterestSampled;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.IndicatorAlertHit;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OPEN_INTEREST_CHANGE alerts on a reading: the change read from the stored readings, the reading's instant as the
 * bar, the evaluation instant cut to microseconds, no reads without alerts, and one failing trigger leaves the others.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4; D-89; Q-45.
 */
class OpenInterestAlertServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:06:40.123456789Z");
    private static final Instant T = Instant.parse("2026-10-14T10:05:00Z");
    private static final UUID PAIR = UUID.randomUUID();
    private static final OpenInterestSampled SAMPLE = new OpenInterestSampled(PAIR, "BTCUSDT", T);

    private final AlertTriggerService triggers = mock(AlertTriggerService.class);
    private final MarketApi market = mock(MarketApi.class);
    private final OpenInterestAlertServiceImpl service =
            new OpenInterestAlertServiceImpl(triggers, market, Clock.fixed(NOW, ZoneOffset.UTC));
    private final NavigableMap<Instant, BigDecimal> stored = new TreeMap<>();

    @BeforeEach
    void readTheStoredReadings() {
        when(market.openInterestAtOrBefore(eq(PAIR), any()))
                .thenAnswer(call -> Optional.ofNullable(stored.floorEntry(call.<Instant>getArgument(1)))
                        .map((Map.Entry<Instant, BigDecimal> entry) ->
                                new OpenInterestSample(entry.getKey(), entry.getValue())));
        when(triggers.tryTriggerOpenInterest(any())).thenReturn(true);
    }

    @Test
    void Q45_aCrossAbove_isOffered_withTheChangeInPercent_andTheReadingAsTheBar() {
        IndicatorAlert alert = alert(ConditionOperator.CROSS_ABOVE, "10");
        when(triggers.activeOpenInterestAlerts(PAIR)).thenReturn(List.of(alert));
        risingFrom5To12Percent();

        assertThat(service.evaluate(SAMPLE)).isOne();

        ArgumentCaptor<IndicatorAlertHit> hit = ArgumentCaptor.forClass(IndicatorAlertHit.class);
        verify(triggers).tryTriggerOpenInterest(hit.capture());
        assertThat(hit.getValue().alert()).isEqualTo(alert);
        assertThat(hit.getValue().symbol()).isEqualTo("BTCUSDT");
        assertThat(hit.getValue().observedValue()).isEqualByComparingTo("12");
        assertThat(hit.getValue().barOpenTime()).isEqualTo(T);
        assertThat(hit.getValue().at()).isEqualTo(Instant.parse("2026-10-14T10:06:40.123456Z"));
    }

    @Test
    void BR20_aConditionNotMet_offersNothing() {
        when(triggers.activeOpenInterestAlerts(PAIR))
                .thenReturn(
                        List.of(alert(ConditionOperator.CROSS_ABOVE, "4"), alert(ConditionOperator.LESS_THAN, "-1")));
        risingFrom5To12Percent();

        assertThat(service.evaluate(SAMPLE)).isZero();
        verify(triggers, never()).tryTriggerOpenInterest(any());
    }

    @Test
    void NSF06_withoutAlertsOnThePair_noReadingIsRead() {
        when(triggers.activeOpenInterestAlerts(PAIR)).thenReturn(List.of());

        assertThat(service.evaluate(SAMPLE)).isZero();
        verify(market, never()).openInterestAtOrBefore(any(), any());
    }

    @Test
    void Q45_withoutABase_nothingIsOffered() {
        when(triggers.activeOpenInterestAlerts(PAIR)).thenReturn(List.of(alert(ConditionOperator.GREATER_THAN, "0")));
        stored.put(T, new BigDecimal("1100"));

        assertThat(service.evaluate(SAMPLE)).isZero();
        verify(triggers, never()).tryTriggerOpenInterest(any());
    }

    @Test
    void D89_aRefusedOrFailingTrigger_isNotCounted_andTheOthersAreStillOffered() {
        IndicatorAlert failing = alert(ConditionOperator.GREATER_THAN, "1");
        IndicatorAlert refused = alert(ConditionOperator.GREATER_THAN, "2");
        IndicatorAlert recorded = alert(ConditionOperator.GREATER_THAN, "3");
        when(triggers.activeOpenInterestAlerts(PAIR)).thenReturn(List.of(failing, refused, recorded));
        when(triggers.tryTriggerOpenInterest(any())).thenAnswer(call -> {
            IndicatorAlert alert = call.<IndicatorAlertHit>getArgument(0).alert();
            if (alert == failing) {
                throw new IllegalStateException("database down");
            }
            return alert == recorded;
        });
        risingFrom5To12Percent();

        assertThat(service.evaluate(SAMPLE)).isOne();
        verify(triggers, times(3)).tryTriggerOpenInterest(any());
    }

    /** 1000 an hour before both readings; 1050 at T − 5 min (5 %), 1120 at T (12 %). */
    private void risingFrom5To12Percent() {
        stored.put(T.minus(Duration.ofMinutes(65)), new BigDecimal("1000"));
        stored.put(T.minus(Duration.ofMinutes(60)), new BigDecimal("1000"));
        stored.put(T.minus(Duration.ofMinutes(5)), new BigDecimal("1050"));
        stored.put(T, new BigDecimal("1120"));
    }

    private static IndicatorAlert alert(ConditionOperator condition, String percent) {
        return new IndicatorAlert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                MarketType.FUTURES,
                AlertIndicator.OPEN_INTEREST_CHANGE,
                "1h",
                condition,
                new BigDecimal(percent),
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
