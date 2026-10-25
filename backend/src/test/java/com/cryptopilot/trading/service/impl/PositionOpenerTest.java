package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The journal record a fill hands over: the plan's values and the entry fee at the rate of the fill's liquidity role,
 * with the T-044 rates (Futures maker 0.02 %, taker 0.05 %).
 */
class PositionOpenerTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000c1");
    private static final UUID USER = UUID.fromString("019b76da-a800-7000-8000-0000000000c2");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-0000000000c3");
    private static final Instant FILLED_AT = Instant.parse("2026-10-25T08:01:00Z");

    private final TradeJournal journal = mock(TradeJournal.class);
    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final PositionOpener opener = new PositionOpener(journal, new FeeRates(settings));

    @BeforeEach
    void rates() {
        when(settings.decimal(FeeRates.FUTURES_MAKER)).thenReturn(new BigDecimal("0.0002"));
        when(settings.decimal(FeeRates.FUTURES_TAKER)).thenReturn(new BigDecimal("0.0005"));
        when(settings.decimal(FeeRates.LIQUIDATION_CLEARANCE)).thenReturn(new BigDecimal("0.0125"));
    }

    /** T-044 (a): a LIMIT reached by a candle, quantity 2 at 100, pays the maker rate: 100 × 2 × 0.02 % = 0.04. */
    @Test
    void BR36_aRestingLimitFill_opensItsPositionWithTheMakerFee() {
        opener.open(executed(LiquidityRole.MAKER, "100"));

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

    /** A Futures SHORT, 10x, quantity 2, filled at {@code price}. */
    private static TradingPlan executed(LiquidityRole role, String price) {
        TradingPlan plan = mock(TradingPlan.class);
        when(plan.getId()).thenReturn(PLAN);
        when(plan.getUserId()).thenReturn(USER);
        when(plan.getPairId()).thenReturn(PAIR);
        when(plan.getStatus()).thenReturn(PlanStatus.EXECUTED);
        when(plan.getMarket()).thenReturn(MarketType.FUTURES);
        when(plan.getDirection()).thenReturn(Direction.SHORT);
        when(plan.getLeverage()).thenReturn(10);
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
                        null,
                        null));
        return plan;
    }
}
