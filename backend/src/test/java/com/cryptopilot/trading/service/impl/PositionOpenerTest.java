package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The journal record a fill hands over: the plan's values, the entry fee at the rate of the fill's liquidity role,
 * with the T-044 rates (Futures maker 0.02 %, taker 0.05 %), and the liquidation price at the fill price (the TD 7.6
 * vector: 10x, MMR 0.004, cum 0).
 */
class PositionOpenerTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000c1");
    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000c2");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000c3");
    private static final UUID JOURNAL = UUID.fromString("019b76da-a800-7000-8000-0000000000c4");
    private static final Instant FILLED_AT = Instant.parse("2026-10-25T08:01:00Z");
    private static final LeverageTier TIER = new LeverageTier(
            1, BigDecimal.ZERO, new BigDecimal("50000"), 125, new BigDecimal("0.004"), BigDecimal.ZERO);

    private final TradeJournal journal = mock(TradeJournal.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final MarketApi market = mock(MarketApi.class);
    private final PositionOpener opener = new PositionOpener(journal, new FeeRates(settings), market);

    @BeforeEach
    void rates() {
        when(settings.decimal(FeeRates.FUTURES_MAKER)).thenReturn(new BigDecimal("0.0002"));
        when(settings.decimal(FeeRates.FUTURES_TAKER)).thenReturn(new BigDecimal("0.0005"));
        when(settings.decimal(FeeRates.LIQUIDATION_CLEARANCE)).thenReturn(new BigDecimal("0.0125"));
        when(settings.decimal(FeeRates.SPOT_MAKER)).thenReturn(new BigDecimal("0.001"));
        when(settings.decimal(FeeRates.SPOT_TAKER)).thenReturn(new BigDecimal("0.001"));
        when(market.leverageBrackets(PAIR)).thenReturn(List.of(TIER));
        when(market.tradablePair(PAIR, MarketType.FUTURES))
                .thenReturn(Optional.of(new TradablePair(
                        PAIR,
                        "BTCUSDT",
                        MarketType.FUTURES,
                        new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5")))));
        when(journal.openSimulatedTrade(any())).thenAnswer(call -> recorded(call.getArgument(0)));
    }

    /** T-044 (a): a LIMIT reached by a candle, quantity 2 at 100, pays the maker rate: 100 × 2 × 0.02 % = 0.04. */
    @Test
    void BR36_aRestingLimitFill_opensItsPositionWithTheMakerFee() {
        TrackedPosition tracked = opener.open(executed(LiquidityRole.MAKER, "100"));

        SimulatedTradeOpening opened = opened();
        assertThat(opened.entryFee()).isEqualTo(new BigDecimal("0.04000000"));
        assertThat(opened.entryLiquidity()).isEqualTo(LiquidityRole.MAKER);
        assertThat(opened.planId()).isEqualTo(PLAN);
        assertThat(opened.userId()).isEqualTo(USER);
        assertThat(opened.pairId()).isEqualTo(PAIR);
        assertThat(opened.market()).isEqualTo(MarketType.FUTURES);
        assertThat(opened.direction()).isEqualTo(Direction.SHORT);
        assertThat(opened.leverage()).isEqualTo(10);
        assertThat(opened.entryPrice()).isEqualByComparingTo("100");
        assertThat(opened.entryTime()).isEqualTo(FILLED_AT);
        assertThat(opened.quantity()).isEqualByComparingTo("2");
        assertThat(opened.stopLoss()).isEqualByComparingTo("105");
        assertThat(opened.takeProfit()).isEqualByComparingTo("90");
        assertThat(tracked.positionId()).isEqualTo(JOURNAL);
        assertThat(tracked.planId()).isEqualTo(PLAN);
        assertThat(tracked.stopLoss()).isEqualByComparingTo("105");
        assertThat(tracked.liquidationPrice()).isEqualTo(opened.liquidationPrice());
        assertThat(tracked.version()).isEqualTo(7);
    }

    /**
     * BR-36: a taker fill pays the taker rate on its own fill price; 100.123 × 2 × 0.05 % = 0.100123, kept at the
     * stored scale of 8 decimals.
     */
    @Test
    void BR36_aTakerFill_paysTheTakerRateOnItsFillPrice_atTheStoredScale() {
        opener.open(executed(LiquidityRole.TAKER, "100.123"));

        assertThat(opened().entryFee()).isEqualTo(new BigDecimal("0.10012300"));
    }

    /**
     * BR-27: the liquidation price is the one of the fill price. SHORT 2 at 100, 10x: (200 + 20) / (2 × 1.004) =
     * 109.5617…, down to the tick 109.56 (TD 7.6); filled through a gap at 101: (202 + 20.2) / 2.008 = 110.6573… →
     * 110.65, not the plan's estimate at 100.
     */
    @Test
    void BR27_theLiquidationPrice_isTheOneOfTheActualFillPrice() {
        opener.open(executed(LiquidityRole.MAKER, "100"));
        assertThat(opened().liquidationPrice()).isEqualByComparingTo("109.56");

        clearInvocations(journal);
        opener.open(executed(LiquidityRole.TAKER, "101"));
        assertThat(opened().liquidationPrice()).isEqualByComparingTo("110.65");
    }

    /** BR-27: without brackets or a tick size the plan's estimate is kept. */
    @Test
    void BR27_withoutBracketsOrTickSize_thePlansEstimateIsKept() {
        when(market.leverageBrackets(PAIR)).thenReturn(List.of());
        opener.open(executed(LiquidityRole.MAKER, "100"));
        assertThat(opened().liquidationPrice()).isEqualByComparingTo("109.00");

        clearInvocations(journal);
        when(market.leverageBrackets(PAIR)).thenReturn(List.of(TIER));
        when(market.tradablePair(PAIR, MarketType.FUTURES)).thenReturn(Optional.empty());
        opener.open(executed(LiquidityRole.MAKER, "100"));
        assertThat(opened().liquidationPrice()).isEqualByComparingTo("109.00");
    }

    /** BR-26, BR-27: a fill whose notional leaves the brackets keeps the plan's estimate. */
    @Test
    void BR27_aFillOutsideTheBrackets_keepsThePlansEstimate() {
        opener.open(executed(LiquidityRole.TAKER, "30000"));

        assertThat(opened().liquidationPrice()).isEqualByComparingTo("109.00");
    }

    /** BR-27: a LONG at 1x that no price liquidates has no liquidation price. */
    @Test
    void BR27_aPositionNoPriceLiquidates_hasNoLiquidationPrice() {
        TradingPlan plan = executed(LiquidityRole.MAKER, "100");
        when(plan.getDirection()).thenReturn(Direction.LONG);
        when(plan.getLeverage()).thenReturn(1);
        when(plan.getStopLoss()).thenReturn(new BigDecimal("95"));

        opener.open(plan);

        assertThat(opened().liquidationPrice()).isNull();
    }

    /** BR-21: a Spot position has no liquidation price and reads no bracket. */
    @Test
    void BR21_aSpotPosition_hasNoLiquidationPrice() {
        TradingPlan plan = executed(LiquidityRole.MAKER, "100");
        when(plan.getMarket()).thenReturn(MarketType.SPOT);
        when(plan.getDirection()).thenReturn(Direction.LONG);
        when(plan.getLeverage()).thenReturn(null);

        opener.open(plan);

        assertThat(opened().liquidationPrice()).isNull();
        verifyNoInteractions(market);
    }

    /** BR-34: only an EXECUTED plan has a position to open. */
    @Test
    void BR34_aPlanThatIsNotExecuted_isRefused_andNothingIsRecorded() {
        TradingPlan active = mock(TradingPlan.class);
        when(active.getId()).thenReturn(PLAN);
        when(active.getStatus()).thenReturn(PlanStatus.ACTIVE);

        assertThatThrownBy(() -> opener.open(active))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");
        verify(journal, never()).openSimulatedTrade(any());
    }

    private SimulatedTradeOpening opened() {
        ArgumentCaptor<SimulatedTradeOpening> opening = ArgumentCaptor.forClass(SimulatedTradeOpening.class);
        verify(journal).openSimulatedTrade(opening.capture());
        return opening.getValue();
    }

    private static OpenTrade recorded(SimulatedTradeOpening opening) {
        return new OpenTrade(
                JOURNAL,
                opening.planId(),
                opening.userId(),
                opening.pairId(),
                opening.market(),
                opening.direction(),
                opening.entryPrice(),
                opening.entryTime(),
                opening.quantity(),
                opening.entryLiquidity(),
                opening.entryFee(),
                opening.stopLoss(),
                opening.takeProfit(),
                opening.liquidationPrice(),
                7);
    }

    /** A Futures SHORT, 10x, quantity 2, capital 1000, filled at {@code price}; its estimate is 109.00. */
    private static TradingPlan executed(LiquidityRole role, String price) {
        TradingPlan plan = mock(TradingPlan.class);
        when(plan.getId()).thenReturn(PLAN);
        when(plan.getUserId()).thenReturn(USER);
        when(plan.getPairId()).thenReturn(PAIR);
        when(plan.getStatus()).thenReturn(PlanStatus.EXECUTED);
        when(plan.getMarket()).thenReturn(MarketType.FUTURES);
        when(plan.getDirection()).thenReturn(Direction.SHORT);
        when(plan.getLeverage()).thenReturn(10);
        when(plan.getCapital()).thenReturn(new BigDecimal("1000"));
        when(plan.getFillPrice()).thenReturn(new BigDecimal(price));
        when(plan.getFillLiquidity()).thenReturn(role);
        when(plan.getExecutedAt()).thenReturn(FILLED_AT);
        when(plan.getStopLoss()).thenReturn(new BigDecimal("105"));
        when(plan.getTakeProfit()).thenReturn(new BigDecimal("90"));
        when(plan.getSnapshot())
                .thenReturn(new PlanSnapshot(
                        new BigDecimal("2"),
                        new BigDecimal("200"),
                        new BigDecimal("20"),
                        null,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        new BigDecimal("109.00"),
                        null));
        return plan;
    }
}
