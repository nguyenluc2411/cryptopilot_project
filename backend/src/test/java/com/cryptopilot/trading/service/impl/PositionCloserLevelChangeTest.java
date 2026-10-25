package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.OpenTrade;
import com.cryptopilot.trading.PositionLevelChange;
import com.cryptopilot.trading.PositionLevels;
import com.cryptopilot.trading.SimulatedTradeClosing;
import com.cryptopilot.trading.TradeJournal;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * TP-E: an exit the engine decided at the version it tracked, while the Trader changed the levels. A change made after
 * the exit's candle opened does not act on that candle, so the exit still closes, at the row's current version; a
 * change made before it means the exit was decided by levels no longer in force, and it is dropped. A liquidation never
 * depends on the levels.
 *
 * <p>Rule: BR-35; D-77, D-119 (5), D-124.
 */
class PositionCloserLevelChangeTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000f2");
    private static final Instant ENTRY = Instant.parse("2026-10-25T08:00:00Z");
    /** The open of the candle the engine decided the exit in. */
    private static final Instant CANDLE = Instant.parse("2026-10-25T09:00:00Z");

    private final TradeJournal journal = mock(TradeJournal.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final FundingService funding = mock(FundingService.class);
    private final MarketApi market = mock(MarketApi.class);
    private final PositionCloser closer = new PositionCloser(
            journal,
            new FeeRates(settings),
            funding,
            new TakerSlippage(settings, market),
            market,
            mock(ApplicationEventPublisher.class));

    @BeforeEach
    void rates() {
        when(settings.decimal(FeeRates.FUTURES_MAKER)).thenReturn(new BigDecimal("0.0002"));
        when(settings.decimal(FeeRates.FUTURES_TAKER)).thenReturn(new BigDecimal("0.0005"));
        when(settings.decimal(FeeRates.LIQUIDATION_CLEARANCE)).thenReturn(new BigDecimal("0.0125"));
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
        when(funding.fundingFee(any(), any(), any(), any(), any())).thenReturn(FundingFee.complete(BigDecimal.ZERO));
        when(journal.closeSimulatedTrade(any())).thenReturn(true);
    }

    /** One change after the candle opened: the exit by the earlier stop closes at the row's version 5. */
    @Test
    void D124_aChangeMadeAfterTheExitsCandleOpened_letsTheExitClose_atTheCurrentVersion() {
        OpenTrade decidedAt = trade(4, "95");
        current(trade(5, "98"), change(CANDLE.plusSeconds(30)));

        assertThat(closer.close(exit(decidedAt, CloseReason.STOP_LOSS, "95"))).isTrue();

        ArgumentCaptor<SimulatedTradeClosing> closing = ArgumentCaptor.forClass(SimulatedTradeClosing.class);
        verify(journal).closeSimulatedTrade(closing.capture());
        assertThat(closing.getValue().version()).isEqualTo(5);
        assertThat(closing.getValue().exitPrice()).isEqualByComparingTo("95");
    }

    /** A change made at or before the candle's open acts on it: the exit by the earlier levels is dropped. */
    @Test
    void D124_aChangeMadeBeforeOrAtTheExitsCandle_dropsTheExit() {
        OpenTrade decidedAt = trade(4, "95");
        current(trade(6, "92"), change(CANDLE.minusSeconds(5)), change(CANDLE.plusSeconds(30)));
        assertThat(closer.close(exit(decidedAt, CloseReason.STOP_LOSS, "95"))).isFalse();

        current(trade(5, "92"), change(CANDLE));
        assertThat(closer.close(exit(decidedAt, CloseReason.STOP_LOSS, "95"))).isFalse();

        verify(journal, never()).closeSimulatedTrade(any());
    }

    /** Only the changes since the tracked version count: an older one, before the candle, does not drop the exit. */
    @Test
    void D124_onlyTheChangesSinceTheTrackedVersion_count() {
        OpenTrade decidedAt = trade(5, "98");
        current(trade(6, "97"), change(CANDLE.minusSeconds(600)), change(CANDLE.plusSeconds(20)));

        assertThat(closer.close(exit(decidedAt, CloseReason.STOP_LOSS, "98"))).isTrue();
    }

    /** A history shorter than the versions moved, or an older version than tracked, is not trusted. */
    @Test
    void D124_aHistoryThatDoesNotExplainTheVersion_dropsTheExit() {
        OpenTrade decidedAt = trade(4, "95");
        current(trade(6, "92"), change(CANDLE.plusSeconds(30)));
        assertThat(closer.close(exit(decidedAt, CloseReason.STOP_LOSS, "95"))).isFalse();

        current(trade(3, "92"));
        assertThat(closer.close(exit(decidedAt, CloseReason.STOP_LOSS, "95"))).isFalse();

        verify(journal, never()).closeSimulatedTrade(any());
    }

    /** A liquidation holds whatever the changes: they never move the liquidation price. */
    @Test
    void D124_aLiquidation_closesWhateverTheLevelChanges() {
        OpenTrade decidedAt = trade(4, "95");
        current(trade(7, "92"), change(CANDLE.minusSeconds(60)));

        assertThat(closer.close(exit(decidedAt, CloseReason.LIQUIDATION, "90.37")))
                .isTrue();
    }

    private void current(OpenTrade trade, PositionLevelChange... changes) {
        when(journal.openTrade(trade.positionId())).thenReturn(Optional.of(trade));
        when(journal.levelsOf(trade.planId()))
                .thenReturn(Optional.of(new PositionLevels(
                        trade.stopLoss(),
                        trade.takeProfit(),
                        new BigDecimal("95"),
                        new BigDecimal("115"),
                        false,
                        changes.length,
                        new ArrayList<>(List.of(changes)))));
    }

    private static PositionExit exit(OpenTrade trade, CloseReason reason, String price) {
        return new PositionExit(
                TrackedPosition.of(trade),
                new Exit(trade.planId(), reason, new BigDecimal(price), CANDLE),
                Excursion.at(trade.entryPrice()));
    }

    private static final UUID POSITION = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();

    private static PositionLevelChange change(Instant at) {
        return new PositionLevelChange(
                POSITION, at, new BigDecimal("95"), new BigDecimal("115"), new BigDecimal("98"), new BigDecimal("115"));
    }

    private static OpenTrade trade(long version, String stop) {
        return new OpenTrade(
                POSITION,
                PLAN,
                UUID.randomUUID(),
                PAIR,
                MarketType.FUTURES,
                Direction.LONG,
                new BigDecimal("100"),
                ENTRY,
                new BigDecimal("2"),
                LiquidityRole.MAKER,
                new BigDecimal("0.04"),
                new BigDecimal(stop),
                new BigDecimal("115"),
                new BigDecimal("90.37"),
                version);
    }
}
