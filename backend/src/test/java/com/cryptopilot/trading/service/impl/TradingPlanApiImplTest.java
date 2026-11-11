package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.PlanContext;
import com.cryptopilot.trading.dto.response.PlanSnapshotResponse;
import com.cryptopilot.trading.dto.response.PlanWarningResponse;
import com.cryptopilot.trading.dto.response.TradingPlanResponse;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import com.cryptopilot.trading.service.TradingPlanService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TradingPlanApiImplTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID PAIR = UUID.randomUUID();

    private final TradingPlanService service = mock(TradingPlanService.class);
    private final TradingPlanApiImpl api = new TradingPlanApiImpl(service);

    @Test
    void BR51_thePlanIsReadThroughTheOwnersView_keepingTheRatiosAndWarnings() {
        TradingPlanResponse plan = plan(snapshot());
        when(service.get(USER, PLAN)).thenReturn(plan);

        PlanContext context = api.planContext(USER, PLAN);

        assertThat(context.pairId()).isEqualTo(PAIR);
        assertThat(context.market()).isEqualTo(MarketType.FUTURES);
        assertThat(context.direction()).isEqualTo(Direction.LONG);
        assertThat(context.riskRewardRatio()).isEqualByComparingTo("2.5");
        assertThat(context.netRiskReward()).isEqualByComparingTo("2.3");
        assertThat(context.estimatedLiquidationPrice()).isEqualByComparingTo("70000");
        assertThat(context.warnings())
                .containsExactly(new PlanContext.Warning(WarningType.LOW_RR, WarningSeverity.WARNING, "Low R:R"));
    }

    @Test
    void BR51_thePlanCarriesNoCapitalAmountOrNote() {
        assertThat(PlanContext.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("capital", "note", "riskAmount", "rewardAmount", "positionQuantity", "notionalValue");
    }

    @Test
    void aPlanStoredWithoutARiskPanel_hasNoRatios() {
        TradingPlanResponse bare = plan(null);
        when(service.get(USER, PLAN)).thenReturn(bare);

        PlanContext context = api.planContext(USER, PLAN);

        assertThat(context.riskRewardRatio()).isNull();
        assertThat(context.warnings()).hasSize(1);
    }

    @Test
    void anotherTradersPlan_isNotFound() {
        when(service.get(USER, PLAN)).thenThrow(new ResourceNotFoundException("TradingPlan", PLAN));

        Assertions.assertThatThrownBy(() -> api.planContext(USER, PLAN)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static PlanSnapshotResponse snapshot() {
        PlanSnapshotResponse snapshot = mock(PlanSnapshotResponse.class);
        when(snapshot.riskRewardRatio()).thenReturn(new BigDecimal("2.5"));
        when(snapshot.netRiskReward()).thenReturn(new BigDecimal("2.3"));
        when(snapshot.feeShareOfRisk()).thenReturn(new BigDecimal("0.08"));
        when(snapshot.estimatedLiquidationPrice()).thenReturn(new BigDecimal("70000"));
        return snapshot;
    }

    private static TradingPlanResponse plan(PlanSnapshotResponse snapshot) {
        TradingPlanResponse plan = mock(TradingPlanResponse.class);
        when(plan.pairId()).thenReturn(PAIR);
        when(plan.market()).thenReturn(MarketType.FUTURES);
        when(plan.direction()).thenReturn(Direction.LONG);
        when(plan.snapshot()).thenReturn(snapshot);
        when(plan.warnings())
                .thenReturn(List.of(new PlanWarningResponse(WarningType.LOW_RR, WarningSeverity.WARNING, "Low R:R")));
        return plan;
    }
}
