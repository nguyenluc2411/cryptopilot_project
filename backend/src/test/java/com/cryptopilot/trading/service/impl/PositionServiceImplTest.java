package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.MarketHealth;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PlanOrderPlacer;
import com.cryptopilot.trading.PlanOrderPlacer.PlanExitQuote;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.dto.response.CloseEstimateResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.PositionStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.TradingPlanService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The estimate and the manual close of a plan's open position on the demo account (RF-33): paper trading prices the
 * close, this service adds the stored entry fee and the funding to the net, and the 404 and 409 answers.
 *
 * <p>Rule: UC-21, BR-36, BR-37, BR-38, BR-39; D-117; MSG21.
 */
class PositionServiceImplTest {

    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000a1");
    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000a2");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000a3");
    private static final Instant ENTRY = Instant.parse("2026-10-25T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-25T20:00:00.123456789Z");

    private final TradingPlanRepository plans = mock(TradingPlanRepository.class);
    private final TradeJournal journal = mock(TradeJournal.class);
    private final TradingPlanService tradingPlans = mock(TradingPlanService.class);
    private final MarketHealth health = mock(MarketHealth.class);
    private final PlanOrderPlacer placer = mock(PlanOrderPlacer.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PlanOrderPlacer> placers = mock(ObjectProvider.class);

    private final PositionServiceImpl service =
            new PositionServiceImpl(plans, journal, health, tradingPlans, Clock.fixed(NOW, ZoneOffset.UTC), placers);

    @BeforeEach
    void setUp() {
        when(placers.getObject()).thenReturn(placer);
        TradingPlan plan = mock(TradingPlan.class);
        when(plan.getStatus()).thenReturn(PlanStatus.EXECUTED);
        when(plans.findByIdAndUserId(PLAN, USER)).thenReturn(Optional.of(plan));
    }

    /**
     * MSG21: a Futures LONG 2 at 100 (entry fee 0.04) quoted at 110 with a taker fee of 0.11 and a funding of −0.0202:
     * price result (110 − 100) × 2 = 20, net 20 − 0.04 − 0.11 − 0.0202 = 19.8298.
     */
    @Test
    void UC21_theEstimate_ofAFuturesLong_addsTheEntryFeeAndTheFunding() {
        OpenTrade trade = open(MarketType.FUTURES, Direction.LONG);
        when(placer.quoteClose(USER, trade)).thenReturn(quote("110", "2", "0.11", new BigDecimal("-0.0202")));

        CloseEstimateResponse estimate = service.closeEstimate(USER, PLAN);

        assertThat(estimate.currentPrice()).isEqualByComparingTo("110");
        assertThat(estimate.estimatedExitPrice()).isEqualByComparingTo("110");
        assertThat(estimate.priceResult()).isEqualByComparingTo("20");
        assertThat(estimate.entryFee()).isEqualByComparingTo("0.04");
        assertThat(estimate.estimatedExitFee()).isEqualByComparingTo("0.11");
        assertThat(estimate.fundingSoFar()).isEqualByComparingTo("-0.0202");
        assertThat(estimate.fundingProvisional()).isFalse();
        assertThat(estimate.estimatedNetPnl()).isEqualByComparingTo("19.8298");
        assertThat(estimate.at()).isEqualTo(NOW);
    }

    /** A SHORT gains when the price falls: (100 − 95) × 2 = 10, net 10 − 0.04 − 0.0475 = 9.9125. */
    @Test
    void UC21_theEstimate_ofAShort_gainsWhenThePriceFalls() {
        OpenTrade trade = open(MarketType.FUTURES, Direction.SHORT);
        when(placer.quoteClose(USER, trade)).thenReturn(quote("95", "2", "0.0475", BigDecimal.ZERO));

        CloseEstimateResponse estimate = service.closeEstimate(USER, PLAN);

        assertThat(estimate.priceResult()).isEqualByComparingTo("10");
        assertThat(estimate.estimatedNetPnl()).isEqualByComparingTo("9.9125");
    }

    /** BR-37: a Spot position has no funding: none is shown and none counts in the net. */
    @Test
    void UC21_theEstimate_ofASpotPosition_hasNoFunding() {
        OpenTrade trade = open(MarketType.SPOT, Direction.LONG);
        when(placer.quoteClose(USER, trade)).thenReturn(quote("104", "1.998", "0.207792", null));

        CloseEstimateResponse estimate = service.closeEstimate(USER, PLAN);

        assertThat(estimate.fundingSoFar()).isNull();
        // (104 − 100) × 1.998 = 7.992; 7.992 − 0.04 − 0.207792 = 7.744208.
        assertThat(estimate.estimatedNetPnl()).isEqualByComparingTo("7.744208");
    }

    /** UC-21: the close is the paper MARKET order, then the plan as it now stands. */
    @Test
    void UC21_theClose_isPlacedOnTheDemoAccount_andAnswersThePlan() {
        OpenTrade trade = open(MarketType.SPOT, Direction.LONG);
        TradingPlanResponse answered = mock(TradingPlanResponse.class);
        when(tradingPlans.get(USER, PLAN)).thenReturn(answered);

        assertThat(service.closePosition(USER, PLAN)).isSameAs(answered);

        verify(health).requireTrading(MarketType.SPOT);
        verify(placer).close(USER, trade);
    }

    /** MSG41: another Trader's plan is not found. */
    @Test
    void MSG41_anotherTradersPlan_isNotFound() {
        when(plans.findByIdAndUserId(PLAN, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeEstimate(USER, PLAN)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.closePosition(USER, PLAN)).isInstanceOf(ResourceNotFoundException.class);
    }

    /** MSG43: a plan without an OPEN position has nothing to close. */
    @Test
    void MSG43_aPlanWithoutAnOpenPosition_isAConflict() {
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of());

        assertThatThrownBy(() -> service.closePosition(USER, PLAN)).isInstanceOf(IllegalPlanStateException.class);
        verify(placer, never()).close(any(), any());
    }

    /** MSG43: a record of the former engine, from before V77, has no paper position to close. */
    @Test
    void MSG43_aRecordOfTheFormerEngine_isNotClosedOnTheDemoAccount() {
        OpenTrade trade = trade(MarketType.SPOT, Direction.LONG);
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(trade));
        when(journal.positionOf(PLAN)).thenReturn(Optional.of(recorded(false)));

        assertThatThrownBy(() -> service.closePosition(USER, PLAN)).isInstanceOf(IllegalPlanStateException.class);
        verify(placer, never()).close(any(), any());
    }

    private OpenTrade open(MarketType market, Direction direction) {
        OpenTrade trade = trade(market, direction);
        when(journal.openSimulatedTrades(USER)).thenReturn(List.of(trade));
        when(journal.positionOf(PLAN)).thenReturn(Optional.of(recorded(true)));
        return trade;
    }

    private static OpenTrade trade(MarketType market, Direction direction) {
        return new OpenTrade(
                UUID.randomUUID(),
                PLAN,
                USER,
                PAIR,
                market,
                direction,
                new BigDecimal("100"),
                ENTRY,
                new BigDecimal("2"),
                LiquidityRole.TAKER,
                new BigDecimal("0.04"),
                new BigDecimal(direction == Direction.LONG ? "95" : "105"),
                null,
                null,
                1);
    }

    private static RecordedPosition recorded(boolean onDemoAccount) {
        return new RecordedPosition(
                PLAN,
                PositionStatus.OPEN,
                new BigDecimal("100"),
                new BigDecimal("2"),
                ENTRY,
                new BigDecimal("0.04"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                onDemoAccount);
    }

    private static PlanExitQuote quote(String price, String quantity, String exitFee, BigDecimal funding) {
        BigDecimal at = new BigDecimal(price);
        return new PlanExitQuote(at, at, new BigDecimal(quantity), new BigDecimal(exitFee), funding);
    }
}
