package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.cryptopilot.trading.SimulatedTradeClosing;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.model.Excursion;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.service.FundingService;
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
 * The close of a position the engine decided to exit, with the T-044 rates (Futures maker 0.02 %, taker 0.05 %,
 * clearance 1.25 %; Spot 0.1 %): the T-044 vectors (a) to (c), the stop loss slippage, the stored entry fee, the
 * incomplete funding and the compare-and-set.
 *
 * <p>Rule: BR-35, BR-36, BR-37, BR-38; D-97 and its note of 2026-10-24; D-111 E5; Q-56, Q-57, Q-58.
 */
class PositionCloserTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000f1");
    private static final Instant ENTRY = Instant.parse("2026-10-25T08:00:00Z");
    private static final Instant EXIT = Instant.parse("2026-10-25T20:00:00Z");

    private final TradeJournal journal = mock(TradeJournal.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final FundingService funding = mock(FundingService.class);
    private final MarketApi market = mock(MarketApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PositionCloser closer = new PositionCloser(
            journal, new FeeRates(settings), funding, new TakerSlippage(settings, market), market, events);

    @BeforeEach
    void rates() {
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
        when(market.leverageBrackets(PAIR))
                .thenReturn(List.of(new LeverageTier(
                        1, BigDecimal.ZERO, new BigDecimal("50000"), 125, new BigDecimal("0.004"), BigDecimal.ZERO)));
        when(journal.closeSimulatedTrade(any())).thenReturn(true);
    }

    /** T-044 (a): LONG Futures TP, 2 at 100 (maker 0.04), TP 115: realized 30, fee 0.086, net 29.914. */
    @Test
    void BR35_caseA_aLongTakeProfit_paysTheMakerRate() {
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.04", "95", "115", "90.37");
        when(funding.fundingFee(PAIR, Direction.LONG, new BigDecimal("2"), ENTRY, EXIT))
                .thenReturn(FundingFee.complete(BigDecimal.ZERO));

        assertThat(closer.close(exit(trade, CloseReason.TAKE_PROFIT, "115"))).isTrue();

        SimulatedTradeClosing closing = closing();
        assertThat(closing.positionId()).isEqualTo(trade.positionId());
        assertThat(closing.version()).isEqualTo(4);
        assertThat(closing.reason()).isEqualTo(CloseReason.TAKE_PROFIT);
        assertThat(closing.exitPrice()).isEqualByComparingTo("115");
        assertThat(closing.exitTime()).isEqualTo(EXIT);
        assertThat(closing.realizedPnl()).isEqualByComparingTo("30");
        assertThat(closing.feeAmount()).isEqualByComparingTo("0.086");
        assertThat(closing.fundingFeeAmount()).isEqualByComparingTo("0");
        assertThat(closing.fundingIncomplete()).isFalse();
        PositionClosed closed = published();
        assertThat(closed.netPnl()).isEqualByComparingTo("29.914");
        assertThat(closed.provisional()).isFalse();
        assertThat(closed.symbol()).isEqualTo("BTCUSDT");
        assertThat(closed.positionId()).isEqualTo(trade.positionId());
        assertThat(closed.planId()).isEqualTo(trade.planId());
        assertThat(closed.userId()).isEqualTo(trade.userId());
        assertThat(closed.closedAt()).isEqualTo(EXIT);
    }

    /** TD 7.8 vector: case (a) with one funding at mark 101, rate 0.0001: funding −0.0202, net 29.8938. */
    @Test
    void BR37_caseAWithFunding_theNetIncludesTheSignedFunding() {
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.04", "95", "115", "90.37");
        when(funding.fundingFee(PAIR, Direction.LONG, new BigDecimal("2"), ENTRY, EXIT))
                .thenReturn(FundingFee.complete(new BigDecimal("-0.0202")));

        closer.close(exit(trade, CloseReason.TAKE_PROFIT, "115"));

        assertThat(closing().fundingFeeAmount()).isEqualByComparingTo("-0.0202");
        assertThat(published().netPnl()).isEqualByComparingTo("29.8938");
    }

    /** T-044 (b): SHORT Futures SL, 2 at 100 (taker 0.1), SL 105: realized −10, fee 0.205, net −10.205. */
    @Test
    void BR35_caseB_aShortStopLoss_paysTheTakerRate() {
        OpenTrade trade = futures(Direction.SHORT, LiquidityRole.TAKER, "0.1", "105", "90", "109.56");
        when(funding.fundingFee(any(), any(), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));

        closer.close(exit(trade, CloseReason.STOP_LOSS, "105"));

        SimulatedTradeClosing closing = closing();
        assertThat(closing.exitPrice()).isEqualByComparingTo("105");
        assertThat(closing.realizedPnl()).isEqualByComparingTo("-10");
        assertThat(closing.feeAmount()).isEqualByComparingTo("0.205");
        assertThat(published().netPnl()).isEqualByComparingTo("-10.205");
    }

    /**
     * T-044 (c), TD 7.8: LONG Futures liquidated at 90.37, 2 at 100 (maker 0.04): realized −19.26, clearance fee
     * min(2.25925, MM 0.72296) = 0.72296 instead of a taker fee, fee 0.76296, net −20.02296.
     */
    @Test
    void BR36_caseC_aLiquidation_paysTheClearanceFee() {
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.04", "80", "115", "90.37");
        when(funding.fundingFee(any(), any(), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));

        closer.close(exit(trade, CloseReason.LIQUIDATION, "90.37"));

        SimulatedTradeClosing closing = closing();
        assertThat(closing.exitPrice()).isEqualByComparingTo("90.37");
        assertThat(closing.realizedPnl()).isEqualByComparingTo("-19.26");
        assertThat(closing.feeAmount()).isEqualByComparingTo("0.76296");
        assertThat(published().netPnl()).isEqualByComparingTo("-20.02296");
        verify(settings, never()).integer(TradingPlanServiceImpl.SLIPPAGE_BPS);
    }

    /**
     * D-111 E5: a stop loss is a stop-market order: at 10 bps a Spot LONG stopped at 59000 sells at 58941; the take
     * profit and the liquidation never slip. Spot: realized (58941 − 60000) × 0.5 = −529.5, fee 30 + 29.4705, no
     * funding, net −588.9705.
     */
    @Test
    void D111E5_aStopLoss_slipsAgainstTheTrader_andSpotHasNoFunding() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);
        OpenTrade trade = spot("59000", "65000");

        closer.close(exit(trade, CloseReason.STOP_LOSS, "59000"));

        SimulatedTradeClosing closing = closing();
        assertThat(closing.exitPrice()).isEqualByComparingTo("58941");
        assertThat(closing.realizedPnl()).isEqualByComparingTo("-529.5");
        assertThat(closing.feeAmount()).isEqualByComparingTo("59.4705");
        assertThat(closing.fundingFeeAmount()).isNull();
        assertThat(closing.fundingIncomplete()).isFalse();
        assertThat(closing.missingFundingTimes()).isEmpty();
        assertThat(published().netPnl()).isEqualByComparingTo("-588.9705");
        verifyNoInteractions(funding);
    }

    /** D-111 E7: a take profit at 10 bps still fills at its own price. */
    @Test
    void BR36_aTakeProfit_neverSlips() {
        when(settings.integer(TradingPlanServiceImpl.SLIPPAGE_BPS)).thenReturn(10);

        closer.close(exit(spot("59000", "65000"), CloseReason.TAKE_PROFIT, "65000"));

        assertThat(closing().exitPrice()).isEqualByComparingTo("65000");
    }

    /** D-119: the entry fee is the one stored at the open, not recomputed at today's rate. */
    @Test
    void BR36_theFeeAmount_isTheStoredEntryFeePlusTheExitFee() {
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.05", "95", "115", null);
        when(funding.fundingFee(any(), any(), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));

        closer.close(exit(trade, CloseReason.TAKE_PROFIT, "115"));

        assertThat(closing().feeAmount()).isEqualByComparingTo("0.096");
    }

    /**
     * D-97 note of 2026-10-24: one settlement missing never blocks the close; the known sum, the flag and the missing
     * time are stored, and the net is provisional.
     */
    @Test
    void BR37_anIncompleteFunding_closesWithTheKnownSum_theFlagAndTheMissingTime() {
        Instant missing = Instant.parse("2026-10-25T16:00:00Z");
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.04", "95", "115", "90.37");
        when(funding.fundingFee(any(), any(), any(), any(), any()))
                .thenReturn(FundingFee.partial(new BigDecimal("-0.0101"), List.of(missing)));

        assertThat(closer.close(exit(trade, CloseReason.TAKE_PROFIT, "115"))).isTrue();

        SimulatedTradeClosing closing = closing();
        assertThat(closing.fundingFeeAmount()).isEqualByComparingTo("-0.0101");
        assertThat(closing.fundingIncomplete()).isTrue();
        assertThat(closing.missingFundingTimes()).containsExactly(missing);
        PositionClosed closed = published();
        assertThat(closed.netPnl()).isEqualByComparingTo("29.9039");
        assertThat(closed.provisional()).isTrue();
    }

    @Test
    void BR35_aPositionClosedFirst_isNotClosedAgain_andNothingIsPublished() {
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.04", "95", "115", null);
        PositionExit exit = exit(trade, CloseReason.TAKE_PROFIT, "115");
        when(journal.openTrade(trade.positionId())).thenReturn(Optional.empty());

        assertThat(closer.close(exit)).isFalse();

        verify(journal, never()).closeSimulatedTrade(any());
        verifyNoInteractions(events, funding);
    }

    /** D-119, TP-E: levels changed since the engine read them: the exit was decided on old levels and is dropped. */
    @Test
    void D119_aPositionWhoseVersionMoved_isNotClosed() {
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.04", "95", "115", null);
        PositionExit exit = exit(trade, CloseReason.TAKE_PROFIT, "115");
        when(journal.openTrade(trade.positionId())).thenReturn(Optional.of(withVersion(trade, 5)));

        assertThat(closer.close(exit)).isFalse();

        verify(journal, never()).closeSimulatedTrade(any());
        verifyNoInteractions(events);
    }

    /** NSF-07: the compare-and-set lost the race: nothing is published. */
    @Test
    void BR35_aCompareAndSetThatChangesNothing_publishesNothing() {
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.04", "95", "115", null);
        when(funding.fundingFee(any(), any(), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));
        when(journal.closeSimulatedTrade(any())).thenReturn(false);

        assertThat(closer.close(exit(trade, CloseReason.TAKE_PROFIT, "115"))).isFalse();

        verifyNoInteractions(events);
    }

    /** A pair whose symbol cannot be read still closes; the event names no symbol. */
    @Test
    void BR35_aPairWithoutASymbol_stillCloses() {
        OpenTrade trade = futures(Direction.LONG, LiquidityRole.MAKER, "0.04", "95", "115", null);
        when(funding.fundingFee(any(), any(), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));
        when(market.tradablePair(PAIR, MarketType.FUTURES)).thenReturn(Optional.empty());

        assertThat(closer.close(exit(trade, CloseReason.TAKE_PROFIT, "115"))).isTrue();

        assertThat(published().symbol()).isNull();
    }

    private SimulatedTradeClosing closing() {
        ArgumentCaptor<SimulatedTradeClosing> closing = ArgumentCaptor.forClass(SimulatedTradeClosing.class);
        verify(journal).closeSimulatedTrade(closing.capture());
        return closing.getValue();
    }

    private PositionClosed published() {
        ArgumentCaptor<PositionClosed> closed = ArgumentCaptor.forClass(PositionClosed.class);
        verify(events).publishEvent(closed.capture());
        return closed.getValue();
    }

    private PositionExit exit(OpenTrade trade, CloseReason reason, String price) {
        when(journal.openTrade(trade.positionId())).thenReturn(Optional.of(trade));
        return new PositionExit(
                TrackedPosition.of(trade),
                new Exit(trade.planId(), reason, new BigDecimal(price), EXIT),
                Excursion.at(trade.entryPrice()));
    }

    /** Futures, quantity 2 at 100, version 4. */
    private static OpenTrade futures(
            Direction direction, LiquidityRole role, String entryFee, String stop, String target, String liquidation) {
        return new OpenTrade(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                MarketType.FUTURES,
                direction,
                new BigDecimal("100"),
                ENTRY,
                new BigDecimal("2"),
                role,
                new BigDecimal(entryFee),
                new BigDecimal(stop),
                new BigDecimal(target),
                liquidation == null ? null : new BigDecimal(liquidation),
                4);
    }

    /** T-044 (d): Spot LONG 0.5 at 60000 by MARKET, taker fee 30. */
    private static OpenTrade spot(String stop, String target) {
        return new OpenTrade(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PAIR,
                MarketType.SPOT,
                Direction.LONG,
                new BigDecimal("60000"),
                ENTRY,
                new BigDecimal("0.5"),
                LiquidityRole.TAKER,
                new BigDecimal("30"),
                new BigDecimal(stop),
                new BigDecimal(target),
                null,
                4);
    }

    private static OpenTrade withVersion(OpenTrade trade, long version) {
        return new OpenTrade(
                trade.positionId(),
                trade.planId(),
                trade.userId(),
                trade.pairId(),
                trade.market(),
                trade.direction(),
                trade.entryPrice(),
                trade.entryTime(),
                trade.quantity(),
                trade.entryLiquidity(),
                trade.entryFee(),
                trade.stopLoss(),
                trade.takeProfit(),
                trade.liquidationPrice(),
                version);
    }
}
