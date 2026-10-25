package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.PositionLevels;
import com.cryptopilot.trading.RecordedPosition;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.dto.response.PositionResponse;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PositionStatus;
import com.cryptopilot.trading.service.FundingService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TP-E: the position block of an executed plan carries its current and original levels, the history of their changes
 * and, while OPEN, the true breakeven stop, provisional while the funding so far may miss a settlement.
 *
 * <p>Rule: BR-37, BR-38, BR-42; UC-20; D-124.
 */
class PositionViewsTest {

    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000e6");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000e7");
    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    private final TradeJournal journal = mock(TradeJournal.class);
    private final MarketApi market = mock(MarketApi.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final FundingService funding = mock(FundingService.class);
    private final PositionViews views = new PositionViews(journal, market, new FeeRates(settings), funding, settings);

    private TradingPlan plan;

    @BeforeEach
    void setUp() {
        PlanFixture fixture = PlanFixture.futuresLong();
        plan = TradingPlan.draft(
                USER,
                PAIR,
                fixture.entryType(EntryType.LIMIT).build(),
                new PlanDetails(
                        EntryType.LIMIT, NOW.plus(Duration.ofDays(7)), null, null, null, AnalysisTimeframe.ONE_HOUR),
                List.of());
        plan.activate(fixture.build(), List.of(), NOW.minusSeconds(3600));
        plan.markExecuted(NOW.minusSeconds(3600), new BigDecimal("100"), LiquidityRole.MAKER);
        when(settings.decimal(FeeRates.FUTURES_MAKER)).thenReturn(new BigDecimal("0.0002"));
        when(settings.decimal(FeeRates.FUTURES_TAKER)).thenReturn(new BigDecimal("0.0005"));
        when(settings.decimal(FeeRates.LIQUIDATION_CLEARANCE)).thenReturn(new BigDecimal("0.0125"));
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        when(market.tradablePair(eq(PAIR), any()))
                .thenAnswer(call -> Optional.of(new TradablePair(
                        PAIR,
                        "BTCUSDT",
                        call.getArgument(1),
                        new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5")))));
    }

    /** An open position whose funding may miss a settlement: the breakeven is given, tagged provisional. */
    @Test
    void D124_anOpenPosition_answersItsBreakeven_provisionalWithAnIncompleteFunding() {
        open();
        when(funding.fundingSoFar(eq(PAIR), any(), any(), any()))
                .thenReturn(FundingFee.partial(new BigDecimal("-0.02"), List.of(NOW.minusSeconds(60))));

        PositionResponse position = views.of(plan);

        assertThat(position.breakevenPrice()).isGreaterThan(new BigDecimal("100"));
        assertThat(position.breakevenProvisional()).isTrue();
    }

    /** The levels the journal holds, with the history and the flag, are the block's. */
    @Test
    void D124_theBlock_showsTheJournalsLevels_andTheirHistory() {
        open();
        when(funding.fundingSoFar(eq(PAIR), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));
        PositionLevelChange change = new PositionLevelChange(
                UUID.randomUUID(),
                NOW.minusSeconds(30),
                new BigDecimal("95"),
                new BigDecimal("110"),
                new BigDecimal("93"),
                new BigDecimal("110"));
        when(journal.levelsOf(plan.getId()))
                .thenReturn(Optional.of(new PositionLevels(
                        new BigDecimal("93"),
                        new BigDecimal("110"),
                        new BigDecimal("95"),
                        new BigDecimal("110"),
                        true,
                        1,
                        List.of(change))));

        PositionResponse position = views.of(plan);

        assertThat(position.stopLoss()).isEqualByComparingTo("93");
        assertThat(position.originalStopLoss()).isEqualByComparingTo("95");
        assertThat(position.stopWidened()).isTrue();
        assertThat(position.levelChangeCount()).isEqualTo(1);
        assertThat(position.levelChanges()).singleElement().satisfies(row -> {
            assertThat(row.changedAt()).isEqualTo(change.changedAt());
            assertThat(row.newStopLoss()).isEqualByComparingTo("93");
        });
        assertThat(position.breakevenProvisional()).isFalse();
    }

    /** Without levels from the journal the plan's own are shown; a closed position has no breakeven. */
    @Test
    void D124_aClosedPosition_hasNoBreakeven_andFallsBackToThePlansLevels() {
        when(journal.positionOf(plan.getId()))
                .thenReturn(Optional.of(new RecordedPosition(
                        plan.getId(),
                        PositionStatus.CLOSED,
                        new BigDecimal("100"),
                        new BigDecimal("2"),
                        NOW.minusSeconds(3600),
                        new BigDecimal("0.04"),
                        null,
                        new BigDecimal("110"),
                        NOW,
                        CloseReason.TAKE_PROFIT,
                        new BigDecimal("20"),
                        new BigDecimal("0.084"),
                        BigDecimal.ZERO,
                        false)));

        PositionResponse position = views.of(plan);

        assertThat(position.breakevenPrice()).isNull();
        assertThat(position.stopLoss()).isEqualByComparingTo("95");
        assertThat(position.originalTakeProfit()).isEqualByComparingTo("110");
        assertThat(position.levelChanges()).isEmpty();
        verifyNoInteractions(funding);
    }

    /** A pair whose tick cannot be read gives no breakeven; a plan with no record gives no block. */
    @Test
    void D124_withoutATick_noBreakeven_andWithoutARecord_noBlock() {
        open();
        when(market.tradablePair(eq(PAIR), any())).thenReturn(Optional.empty());

        assertThat(views.of(plan).breakevenPrice()).isNull();

        when(journal.positionOf(plan.getId())).thenReturn(Optional.empty());
        assertThat(views.of(plan)).isNull();
    }

    private void open() {
        when(journal.positionOf(plan.getId()))
                .thenReturn(Optional.of(RecordedPosition.open(
                        plan.getId(),
                        new BigDecimal("100"),
                        new BigDecimal("2"),
                        NOW.minusSeconds(3600),
                        new BigDecimal("0.04"),
                        new BigDecimal("80.32"))));
    }
}
