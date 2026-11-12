package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.SetupScoreReading;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.SimulatedFillLearning;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.PlanFilled;
import com.cryptopilot.trading.event.SetupScoreDropped;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
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
import org.springframework.context.ApplicationEventPublisher;

/**
 * The journal record a fill hands over: the plan's values, the entry fee at the rate of the fill's liquidity role,
 * with the T-044 rates (Futures maker 0.02 %, taker 0.05 %), and the liquidation price at the fill price (the TD 7.6
 * vector: 10x, MMR 0.004, cum 0), and the {@link PlanFilled} event it publishes for MSG22 (Q-81).
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
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PositionOpener opener = new PositionOpener(
            journal, new FeeRates(settings), market, new FillLearningCapture(market, settings), events);

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
        verify(market, never()).leverageBrackets(any());
    }

    /**
     * Q-81, MSG22: a fill publishes one {@link PlanFilled} with the position it opened, the stored fill price and role,
     * the snapshot's quantity, the fill time and the pair's symbol on the plan's market.
     */
    @Test
    void Q81_aFill_publishesPlanFilled_withTheStoredFillAndThePairsSymbol() {
        opener.open(executed(LiquidityRole.TAKER, "100.123"));

        verify(events)
                .publishEvent(new PlanFilled(
                        PLAN,
                        JOURNAL,
                        USER,
                        MarketType.FUTURES,
                        Direction.SHORT,
                        "BTCUSDT",
                        new BigDecimal("100.123"),
                        new BigDecimal("2"),
                        LiquidityRole.TAKER,
                        FILLED_AT));
    }

    /** Q-81: a Spot fill names the Spot pair's symbol, read on Spot. */
    @Test
    void Q81_aSpotFill_namesTheSpotSymbol() {
        TradingPlan plan = executed(LiquidityRole.MAKER, "100");
        when(plan.getMarket()).thenReturn(MarketType.SPOT);
        when(plan.getLeverage()).thenReturn(null);
        when(market.tradablePair(PAIR, MarketType.SPOT))
                .thenReturn(Optional.of(new TradablePair(
                        PAIR,
                        "ETHUSDT",
                        MarketType.SPOT,
                        new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.0001"), new BigDecimal("5")))));

        opener.open(plan);

        assertThat(published().market()).isEqualTo(MarketType.SPOT);
        assertThat(published().symbol()).isEqualTo("ETHUSDT");
    }

    /** Q-81: a pair that cannot be read still opens the position and publishes the fill, without a symbol. */
    @Test
    void Q81_aPairThatCannotBeRead_publishesTheFillWithoutASymbol() {
        when(market.tradablePair(PAIR, MarketType.FUTURES)).thenReturn(Optional.empty());

        opener.open(executed(LiquidityRole.MAKER, "100"));

        assertThat(published().symbol()).isNull();
        assertThat(published().positionId()).isEqualTo(JOURNAL);
    }

    /** Q-81: a journal that refuses the record (the fill rolls back) publishes nothing. */
    @Test
    void Q81_aJournalThatRefusesTheRecord_publishesNothing() {
        doThrow(new IllegalStateException("already recorded")).when(journal).openSimulatedTrade(any());

        assertThatThrownBy(() -> opener.open(executed(LiquidityRole.MAKER, "100")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
    }

    /** BR-70: the learning data of the fill goes with its opening, so the record holds it from the start. */
    @Test
    void BR70_theLearningDataOfTheFill_goesWithItsOpening() {
        TradingPlan plan = executed(LiquidityRole.TAKER, "99");
        when(plan.getPlannedEntryPrice()).thenReturn(new BigDecimal("100"));
        when(plan.getAnalysisTimeframe()).thenReturn(AnalysisTimeframe.ONE_HOUR);

        opener.open(plan);

        SimulatedTradeOpening opened = opened();
        assertThat(opened.learning().plannedEntryPrice()).isEqualByComparingTo("100");
        // SHORT sold at 99 against 100 planned, stop 105: −1 × (99 − 100) / 5 = +0.2 R.
        assertThat(opened.learning().entryDeviationR()).isEqualByComparingTo("0.2");
        assertThat(opened.learning().riskAmount()).isEqualByComparingTo("10");
        assertThat(opened.learning().analysisTimeframe()).isEqualTo("1h");
        assertThat(opened.entryLiquidity()).isEqualTo(LiquidityRole.TAKER);
    }

    /** MSG55: a fill whose setup score fell by the threshold publishes the drop beside the fill. */
    @Test
    void MSG55_aDroppedSetupScore_publishesSetupScoreDropped_besidePlanFilled() {
        TradingPlan plan = dropped("70.00", 55);

        opener.open(plan);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues().getFirst()).isInstanceOf(PlanFilled.class);
        assertThat(published.getAllValues().getLast())
                .isEqualTo(new SetupScoreDropped(
                        PLAN,
                        JOURNAL,
                        USER,
                        Direction.SHORT,
                        "BTCUSDT",
                        new BigDecimal("70.00"),
                        new BigDecimal("55.00"),
                        FILLED_AT));
        assertThat(opened().learning().setupScoreDropWarned()).isTrue();
    }

    /** MSG55: a drop below the threshold publishes only the fill. */
    @Test
    void MSG55_aDropBelowTheThreshold_publishesOnlyTheFill() {
        opener.open(dropped("69.00", 55));

        verify(events, times(1)).publishEvent(any(PlanFilled.class));
        verify(events, never()).publishEvent(any(SetupScoreDropped.class));
    }

    /** BR-70: whatever fails while the learning data is taken, the fill is recorded without it. */
    @Test
    void BR70_aFailureTakingTheLearningData_neverBlocksTheFill() {
        TradingPlan plan = executed(LiquidityRole.MAKER, "100");
        when(plan.getPlannedEntryPrice()).thenThrow(new IllegalStateException("broken plan"));

        TrackedPosition tracked = opener.open(plan);

        assertThat(tracked.positionId()).isEqualTo(JOURNAL);
        assertThat(opened().learning()).isEqualTo(SimulatedFillLearning.none());
        assertThat(published().planId()).isEqualTo(PLAN);
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
        verifyNoInteractions(events);
    }

    private SimulatedTradeOpening opened() {
        ArgumentCaptor<SimulatedTradeOpening> opening = ArgumentCaptor.forClass(SimulatedTradeOpening.class);
        verify(journal).openSimulatedTrade(opening.capture());
        return opening.getValue();
    }

    private PlanFilled published() {
        ArgumentCaptor<PlanFilled> filled = ArgumentCaptor.forClass(PlanFilled.class);
        verify(events).publishEvent(filled.capture());
        return filled.getValue();
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

    /** A plan whose setup score at activation was {@code atPlan} and is {@code atFill} at the fill (SWING v1, 4h). */
    private TradingPlan dropped(String atPlan, int atFill) {
        TradingPlan plan = executed(LiquidityRole.MAKER, "100");
        when(plan.getSetupScoreAtPlan()).thenReturn(new BigDecimal(atPlan));
        when(plan.getSetupScorePreset()).thenReturn("SWING v1");
        when(settings.integer(FillLearningCapture.SETUP_SCORE_DROP_WARN)).thenReturn(15);
        when(market.presetTimeframe(SetupStyle.SWING)).thenReturn("4h");
        when(market.closedCandleSetupScore(PAIR, MarketType.FUTURES, "4h", SetupStyle.SWING, "v1", FILLED_AT))
                .thenReturn(new SetupScoreReading(atFill, SetupStyle.SWING, "v1", "4h", FILLED_AT));
        return plan;
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
