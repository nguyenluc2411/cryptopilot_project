package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.SimulatedTradeClosing;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.dto.response.CloseEstimateResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.FundingService;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The estimate and the manual close of an open position, with the real {@link PositionCloser} and the T-044 rates
 * (Futures maker 0.02 %, taker 0.05 %; Spot 0.1 %): the estimate's figures, that it equals the stored close at an
 * unmoved price, and the 404, 409 and 503 answers.
 *
 * <p>Rule: UC-21, BR-36, BR-37, BR-38, BR-39; D-111 E2, E5; MSG21.
 */
class PositionServiceImplTest {

    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000a1");
    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000a2");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000a3");
    private static final Instant ENTRY = Instant.parse("2026-10-25T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-25T20:00:00.123456789Z");

    private final TradingPlanRepository plans = mock(TradingPlanRepository.class);
    private final TradeJournal journal = mock(TradeJournal.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final FundingService funding = mock(FundingService.class);
    private final MarketApi market = mock(MarketApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TradingPlanService tradingPlans = mock(TradingPlanService.class);
    private final PositionCloser closer = new PositionCloser(
            journal, new FeeRates(settings), funding, new TakerSlippage(settings, market), market, events);
    private final PositionServiceImpl service =
            new PositionServiceImpl(plans, journal, closer, market, tradingPlans, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(settings.decimal(FeeRates.FUTURES_MAKER)).thenReturn(new BigDecimal("0.0002"));
        when(settings.decimal(FeeRates.FUTURES_TAKER)).thenReturn(new BigDecimal("0.0005"));
        when(settings.decimal(FeeRates.LIQUIDATION_CLEARANCE)).thenReturn(new BigDecimal("0.0125"));
        when(settings.decimal(FeeRates.SPOT_MAKER)).thenReturn(new BigDecimal("0.001"));
        when(settings.decimal(FeeRates.SPOT_TAKER)).thenReturn(new BigDecimal("0.001"));
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(0);
        when(market.tradablePair(any(), any()))
                .thenAnswer(call -> Optional.of(new TradablePair(
                        PAIR,
                        "BTCUSDT",
                        call.getArgument(1),
                        new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5")))));
        TradingPlan plan = mock(TradingPlan.class);
        when(plan.getStatus()).thenReturn(PlanStatus.EXECUTED);
        when(plans.findByIdAndUserId(PLAN, USER)).thenReturn(Optional.of(plan));
        when(journal.closeSimulatedTrade(any())).thenReturn(true);
    }

    /**
     * MSG21, D-111 E5: a Futures LONG 2 at 100 (maker 0.04) at a last price of 110 and 10 bps: exit 110 × 0.999 =
     * 109.89; price result (109.89 − 100) × 2 = 19.78; taker exit fee 109.89 × 2 × 0.0005 = 0.10989; one funding paid,
     * −0.0202, still provisional; net 19.78 − 0.04 − 0.10989 − 0.0202 = 19.60991.
     */
    @Test
    void UC21_theEstimate_ofAFuturesLong_withSlippageAndFunding() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        open(futures(Direction.LONG));
        lastPrice(MarketType.FUTURES, "110");
        when(funding.fundingFee(any(), any(), any(), any(), any()))
                .thenReturn(FundingFee.partial(new BigDecimal("-0.0202"), List.of()));

        CloseEstimateResponse estimate = service.closeEstimate(USER, PLAN);

        assertThat(estimate.currentPrice()).isEqualByComparingTo("110");
        assertThat(estimate.estimatedExitPrice()).isEqualByComparingTo("109.89");
        assertThat(estimate.priceResult()).isEqualByComparingTo("19.78");
        assertThat(estimate.entryFee()).isEqualByComparingTo("0.04");
        assertThat(estimate.estimatedExitFee()).isEqualByComparingTo("0.10989");
        assertThat(estimate.fundingSoFar()).isEqualByComparingTo("-0.0202");
        assertThat(estimate.fundingProvisional()).isTrue();
        assertThat(estimate.estimatedNetPnl()).isEqualByComparingTo("19.60991");
        assertThat(estimate.at()).isEqualTo(Instant.parse("2026-10-25T20:00:00.123456Z"));
        verify(journal, never()).closeSimulatedTrade(any());
        verify(market, never()).currentMarkPrice(any());
    }

    /** A SHORT buys back: 10 bps above the last price, rounded up to the tick. */
    @Test
    void UC21_theEstimate_ofAShort_buysBackAboveTheLastPrice() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        open(futures(Direction.SHORT));
        lastPrice(MarketType.FUTURES, "95");
        when(funding.fundingFee(any(), any(), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));

        CloseEstimateResponse estimate = service.closeEstimate(USER, PLAN);

        // 95 × 1.001 = 95.095, up to 95.10; (100 − 95.10) × 2 = 9.8.
        assertThat(estimate.estimatedExitPrice()).isEqualByComparingTo("95.10");
        assertThat(estimate.priceResult()).isEqualByComparingTo("9.8");
        assertThat(estimate.fundingProvisional()).isFalse();
    }

    /** BR-37: a Spot position has no funding: null, not provisional. */
    @Test
    void UC21_theEstimate_ofASpotPosition_hasNoFunding() {
        open(spot());
        lastPrice(MarketType.SPOT, "62000");

        CloseEstimateResponse estimate = service.closeEstimate(USER, PLAN);

        // T-044 (d): realized 1000, fee 30 + 31 = 61, net 939.
        assertThat(estimate.priceResult()).isEqualByComparingTo("1000");
        assertThat(estimate.estimatedExitFee()).isEqualByComparingTo("31");
        assertThat(estimate.fundingSoFar()).isNull();
        assertThat(estimate.fundingProvisional()).isFalse();
        assertThat(estimate.estimatedNetPnl()).isEqualByComparingTo("939");
    }

    /** UC-21: at an unmoved price the stored close is exactly the estimate, and it is MANUAL at the close instant. */
    @Test
    void BR39_theStoredClose_equalsTheEstimate_whenThePriceDoesNotMove() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        OpenTrade trade = futures(Direction.LONG);
        open(trade);
        lastPrice(MarketType.FUTURES, "110");
        when(funding.fundingFee(any(), any(), any(), any(), any()))
                .thenReturn(FundingFee.complete(new BigDecimal("-0.0202")));
        TradingPlanResponse detail = mock(TradingPlanResponse.class);
        when(tradingPlans.get(USER, PLAN)).thenReturn(detail);

        CloseEstimateResponse estimate = service.closeEstimate(USER, PLAN);
        assertThat(service.closePosition(USER, PLAN)).isSameAs(detail);

        ArgumentCaptor<SimulatedTradeClosing> stored = ArgumentCaptor.forClass(SimulatedTradeClosing.class);
        verify(journal).closeSimulatedTrade(stored.capture());
        SimulatedTradeClosing closing = stored.getValue();
        assertThat(closing.reason()).isEqualTo(CloseReason.MANUAL);
        assertThat(closing.positionId()).isEqualTo(trade.positionId());
        assertThat(closing.version()).isEqualTo(trade.version());
        assertThat(closing.exitTime()).isEqualTo(estimate.at());
        assertThat(closing.exitPrice()).isEqualByComparingTo(estimate.estimatedExitPrice());
        assertThat(closing.realizedPnl()).isEqualByComparingTo(estimate.priceResult());
        assertThat(closing.feeAmount()).isEqualByComparingTo(estimate.entryFee().add(estimate.estimatedExitFee()));
        assertThat(closing.fundingFeeAmount()).isEqualByComparingTo(estimate.fundingSoFar());
        ArgumentCaptor<PositionClosed> closed = ArgumentCaptor.forClass(PositionClosed.class);
        verify(events).publishEvent(closed.capture());
        assertThat(closed.getValue().netPnl()).isEqualByComparingTo(estimate.estimatedNetPnl());
        assertThat(closed.getValue().closeReason()).isEqualTo(CloseReason.MANUAL);
        assertThat(closed.getValue().pairId()).isEqualTo(PAIR);
    }

    /** NSF-07: the engine's exit committed between the read and the compare-and-set: 409. */
    @Test
    void UC21_aCloseThatLosesTheRaceToTheEngine_isMsg43Conflict() {
        open(spot());
        lastPrice(MarketType.SPOT, "62000");
        when(journal.closeSimulatedTrade(any())).thenReturn(false);

        assertThatThrownBy(() -> service.closePosition(USER, PLAN))
                .isInstanceOf(IllegalPlanStateException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.TRADING_PLAN_STATUS_TRANSITION_INVALID));
        verify(tradingPlans, never()).get(any(), any());
    }

    @Test
    void UC21_anotherTradersPlan_isNotFound() {
        UUID other = UUID.randomUUID();

        assertThatThrownBy(() -> service.closeEstimate(other, PLAN)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.closePosition(other, PLAN)).isInstanceOf(ResourceNotFoundException.class);
    }

    /** A plan never filled, or whose position is closed, has no OPEN position: 409 MSG43. */
    @Test
    void UC21_aPlanWithoutAnOpenPosition_isMsg43Conflict() {
        when(journal.openTradeOfPlan(PLAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeEstimate(USER, PLAN)).isInstanceOf(IllegalPlanStateException.class);
        assertThatThrownBy(() -> service.closePosition(USER, PLAN)).isInstanceOf(IllegalPlanStateException.class);
        verify(journal, never()).closeSimulatedTrade(any());
    }

    /** No current last price, or a pair no longer tradable: 503 MSG43, nothing stored. */
    @Test
    void UC21_withoutACurrentLastPrice_isMsg43Unavailable() {
        open(spot());
        when(market.currentLastPrice(MarketType.SPOT, "BTCUSDT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeEstimate(USER, PLAN))
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));
        when(market.tradablePair(PAIR, MarketType.SPOT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.closePosition(USER, PLAN))
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.MARKET_PRICE_UNAVAILABLE));
        verify(journal, never()).closeSimulatedTrade(any());
    }

    private void open(OpenTrade trade) {
        when(journal.openTradeOfPlan(PLAN)).thenReturn(Optional.of(trade));
        when(journal.openTrade(trade.positionId())).thenReturn(Optional.of(trade));
    }

    private void lastPrice(MarketType marketType, String price) {
        when(market.currentLastPrice(marketType, "BTCUSDT")).thenReturn(Optional.of(new BigDecimal(price)));
    }

    /** Futures 2 at 100, maker entry fee 0.04, version 3. */
    private static OpenTrade futures(Direction direction) {
        boolean isLong = direction == Direction.LONG;
        return new OpenTrade(
                UUID.randomUUID(),
                PLAN,
                USER,
                PAIR,
                MarketType.FUTURES,
                direction,
                new BigDecimal("100"),
                ENTRY,
                new BigDecimal("2"),
                LiquidityRole.MAKER,
                new BigDecimal("0.04"),
                new BigDecimal(isLong ? "90" : "110"),
                new BigDecimal(isLong ? "130" : "70"),
                null,
                3);
    }

    /** T-044 (d): Spot LONG 0.5 at 60000 by MARKET, taker fee 30. */
    private static OpenTrade spot() {
        return new OpenTrade(
                UUID.randomUUID(),
                PLAN,
                USER,
                PAIR,
                MarketType.SPOT,
                Direction.LONG,
                new BigDecimal("60000"),
                ENTRY,
                new BigDecimal("0.5"),
                LiquidityRole.TAKER,
                new BigDecimal("30"),
                new BigDecimal("55000"),
                new BigDecimal("70000"),
                null,
                3);
    }
}
