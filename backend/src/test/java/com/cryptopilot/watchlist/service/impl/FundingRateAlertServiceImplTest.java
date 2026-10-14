package com.cryptopilot.watchlist.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.model.IndicatorAlert;
import com.cryptopilot.watchlist.model.IndicatorAlertHit;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertTriggerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FUNDING_RATE alerts on the predicted funding rate: the percent level against the fraction, a cross measured from the
 * previous reading of the pair, the 1h bar of A-40, and no trigger without a current rate.
 *
 * <p>Rule: NSF-06, BR-19, BR-20; SRS 3.4.4; A-40; Q-30.
 */
class FundingRateAlertServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:12:05.123456789Z");
    private static final UUID PAIR = UUID.randomUUID();
    private static final String SYMBOL = "BTCUSDT";

    private final AlertTriggerService triggers = mock(AlertTriggerService.class);
    private final MarketApi market = mock(MarketApi.class);
    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final FundingRateAlertServiceImpl service =
            new FundingRateAlertServiceImpl(triggers, market, clock, properties());

    @BeforeEach
    void listThePair() {
        when(market.pairListings(any())).thenReturn(List.of(new PairListing(PAIR, SYMBOL, true, true)));
        when(triggers.tryTriggerFundingRate(any())).thenReturn(true);
    }

    @Test
    void BR20_aCrossAbove_firesOnTheSecondReading_inPercent_onTheHoursBar() {
        IndicatorAlert alert = alert(ConditionOperator.CROSS_ABOVE, "0.01");
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(alert));
        rate("0.00005");

        assertThat(service.evaluate()).as("no previous rate yet").isZero();
        clock.advance(Duration.ofSeconds(1));
        rate("0.0001");
        int recorded = service.evaluate();

        ArgumentCaptor<IndicatorAlertHit> hit = ArgumentCaptor.forClass(IndicatorAlertHit.class);
        verify(triggers).tryTriggerFundingRate(hit.capture());
        assertThat(recorded).isOne();
        assertThat(hit.getValue().alert()).isEqualTo(alert);
        assertThat(hit.getValue().symbol()).isEqualTo(SYMBOL);
        assertThat(hit.getValue().observedValue()).isEqualByComparingTo("0.01");
        assertThat(hit.getValue().barOpenTime()).isEqualTo(Instant.parse("2026-10-14T09:00:00Z"));
        assertThat(hit.getValue().at()).isEqualTo(Instant.parse("2026-10-14T09:12:06.123456Z"));
    }

    @Test
    void BR20_aRateStayingOnOneSide_orUnchanged_doesNotCross() {
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(alert(ConditionOperator.CROSS_BELOW, "-0.01")));
        rate("0.0001");
        service.evaluate();
        rate("0.00005");
        service.evaluate();
        service.evaluate();

        verify(triggers, never()).tryTriggerFundingRate(any());
    }

    @Test
    void BR20_aLevelComparison_firesOnEveryReadingItHolds_leavingTheModeToTheDatabase() {
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(alert(ConditionOperator.LESS_THAN, "-0.01")));
        rate("-0.0001");
        assertThat(service.evaluate()).as("equal to the level").isZero();
        rate("-0.00010001");

        service.evaluate();
        service.evaluate();

        verify(triggers, times(2)).tryTriggerFundingRate(any());
    }

    @Test
    void NSF06_withoutACurrentRate_nothingFires_andTheNextRateHasNoPrevious() {
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(alert(ConditionOperator.CROSS_ABOVE, "0.01")));
        rate("0.00005");
        service.evaluate();
        when(market.currentFundingRate(SYMBOL)).thenReturn(Optional.empty());
        assertThat(service.evaluate()).isZero();

        rate("0.0002");

        assertThat(service.evaluate()).as("the gap broke the cross").isZero();
        verify(triggers, never()).tryTriggerFundingRate(any());
    }

    @Test
    void NSF06_aPairNotListed_orNoAlerts_readsNoRate() {
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of());
        assertThat(service.evaluate()).isZero();

        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(alert(ConditionOperator.GREATER_THAN, "0")));
        when(market.pairListings(any())).thenReturn(List.of());
        assertThat(service.evaluate()).isZero();

        verify(market, never()).currentFundingRate(any());
    }

    @Test
    void NSF06_withoutFundingRateAlerts_theMarketIsNotAsked() {
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of());

        service.evaluate();

        verify(market, never()).pairListings(any());
    }

    @Test
    void BR20_aPairWhoseAlertsWentAway_startsAgainWithoutAPreviousRate() {
        IndicatorAlert alert = alert(ConditionOperator.CROSS_ABOVE, "0.01");
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(alert));
        rate("0.00005");
        service.evaluate();
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of());
        service.evaluate();
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(alert));
        rate("0.0002");

        assertThat(service.evaluate()).isZero();
        verify(triggers, never()).tryTriggerFundingRate(any());
    }

    @Test
    void D89_aTriggerTheDatabaseRefuses_isNotCounted() {
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(alert(ConditionOperator.GREATER_THAN, "0")));
        when(triggers.tryTriggerFundingRate(any())).thenReturn(false);
        rate("0.0001");

        assertThat(service.evaluate()).isZero();
        verify(triggers).tryTriggerFundingRate(any());
    }

    @Test
    void D89_aFailingTrigger_isNotCounted_andTheOtherAlertsAreStillOffered() {
        IndicatorAlert failing = alert(ConditionOperator.GREATER_THAN, "0");
        IndicatorAlert recorded = alert(ConditionOperator.GREATER_THAN, "0");
        when(triggers.activeFundingRateAlerts()).thenReturn(List.of(failing, recorded));
        when(triggers.tryTriggerFundingRate(any())).thenAnswer(call -> {
            if (call.<IndicatorAlertHit>getArgument(0).alert() == failing) {
                throw new IllegalStateException("database down");
            }
            return true;
        });
        rate("0.0001");

        assertThat(service.evaluate()).isOne();
        verify(triggers, times(2)).tryTriggerFundingRate(any());
    }

    private void rate(String fraction) {
        when(market.currentFundingRate(SYMBOL)).thenReturn(Optional.of(new BigDecimal(fraction)));
    }

    private static IndicatorAlert alert(ConditionOperator condition, String percent) {
        return new IndicatorAlert(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                MarketType.FUTURES,
                AlertIndicator.FUNDING_RATE,
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

    private static AlertEngineProperties properties() {
        return new AlertEngineProperties(
                true,
                1,
                10,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                new AlertEngineProperties.ExpirySweep(false, "0 * * * * *", "UTC"));
    }
}
