package com.cryptopilot.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.ai.model.AiContextSelection;
import com.cryptopilot.ai.model.ContextSnapshot;
import com.cryptopilot.ai.model.PerformanceContext;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairAnalysis;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.trading.PlanContext;
import com.cryptopilot.trading.TradingPlanApi;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContextSnapshotBuilderTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();

    private final MarketApi market = mock(MarketApi.class);
    private final TradingPlanApi plans = mock(TradingPlanApi.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContextSnapshotBuilder builder =
            new ContextSnapshotBuilder(market, plans, entitlements, json, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void BR51_thePairsAnalysis_isReadAsTheTraderSeesIt_onTheChosenSeries() {
        when(market.pairAnalysis(PAIR, MarketType.FUTURES, "4h", USER)).thenReturn(Optional.of(analysis()));

        ContextSnapshot snapshot =
                builder.build(USER, new AiContextSelection(PAIR, MarketType.FUTURES, "4h", null, false));

        assertThat(snapshot.version()).isEqualTo(ContextSnapshot.VERSION);
        assertThat(snapshot.builtAt()).isEqualTo(NOW);
        assertThat(snapshot.market()).isEqualTo(analysis());
        assertThat(snapshot.plan()).isNull();
        assertThat(snapshot.performance()).isNull();
    }

    @Test
    void BR51_aPlanWithoutAPair_suppliesItsPairMarketAndTimeframe() {
        when(plans.planContext(USER, PLAN)).thenReturn(plan());
        when(market.pairAnalysis(PAIR, MarketType.FUTURES, "1h", USER)).thenReturn(Optional.of(analysis()));

        ContextSnapshot snapshot = builder.build(USER, new AiContextSelection(null, null, null, PLAN, false));

        assertThat(snapshot.plan()).isEqualTo(plan());
        assertThat(snapshot.market()).isNotNull();
    }

    @Test
    void BR51_anotherPairThanThePlans_defaultsToSpotAndTheDefaultTimeframe() {
        UUID other = UUID.randomUUID();
        when(plans.planContext(USER, PLAN)).thenReturn(plan());
        when(market.pairAnalysis(other, MarketType.SPOT, null, USER)).thenReturn(Optional.of(analysis()));

        builder.build(USER, new AiContextSelection(other, null, null, PLAN, false));

        verify(market).pairAnalysis(other, MarketType.SPOT, null, USER);
    }

    @Test
    void BR51_aGeneralQuestion_hasAnEmptySnapshot_storedAsNothing() {
        ContextSnapshot snapshot = builder.build(USER, AiContextSelection.NONE);

        assertThat(snapshot.carriesNothing()).isTrue();
        assertThat(builder.toJson(snapshot)).isNull();
        assertThat(builder.toJson(null)).isNull();
        verifyNoInteractions(market, plans, entitlements);
    }

    @Test
    void BR51_thePerformanceSummary_needsPremium_andSaysItIsNotAvailableYet() {
        ContextSnapshot premium = builder.build(USER, new AiContextSelection(null, null, null, null, true));
        assertThat(premium.performance()).isEqualTo(PerformanceContext.NOT_YET_AVAILABLE);
        verify(entitlements).requireFeature(USER, Feature.AI_PERFORMANCE_CONTEXT);

        doThrow(new BusinessException(ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "PREMIUM only"))
                .when(entitlements)
                .requireFeature(USER, Feature.AI_PERFORMANCE_CONTEXT);
        assertThatThrownBy(() -> builder.build(USER, new AiContextSelection(null, null, null, null, true)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void BR07_aPairThatIsNotEnabledOnTheMarket_isNotFound() {
        when(market.pairAnalysis(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> builder.build(USER, new AiContextSelection(PAIR, null, null, null, false)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void BR51_theStoredSnapshot_carriesNoPersonalData() {
        when(plans.planContext(USER, PLAN)).thenReturn(plan());
        when(market.pairAnalysis(PAIR, MarketType.FUTURES, "1h", USER)).thenReturn(Optional.of(analysis()));
        when(entitlements.hasFeature(any(), any())).thenReturn(true);

        String stored = builder.toJson(builder.build(USER, new AiContextSelection(null, null, null, PLAN, true)));

        assertThat(stored).doesNotContain(USER.toString());
        JsonNode root = json.readTree(stored);
        assertThat(fieldNames(root))
                .doesNotContain(
                        "userId", "email", "displayName", "name", "capital", "note", "riskAmount", "rewardAmount");
        assertThat(root.path("market").path("symbol").asString()).isEqualTo("BTCUSDT");
        assertThat(root.path("market").path("setupScore").asInt()).isEqualTo(72);
        assertThat(root.path("plan").path("riskRewardRatio").asString()).isEqualTo("2.5");
        assertThat(root.path("plan").path("warnings").get(0).path("type").asString())
                .isEqualTo("HIGH_LEVERAGE");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, List<String> names) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                names.add(name);
                collect(node.get(name), names);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, names));
        }
    }

    private static PairAnalysis analysis() {
        return new PairAnalysis(
                "BTCUSDT",
                MarketType.FUTURES,
                "1h",
                NOW.minusSeconds(3600),
                new BigDecimal("78038.12"),
                new PairAnalysis.Indicators(
                        null, null, null, null, new BigDecimal("72"), null, null, null, null, null, null, null),
                new BigDecimal("76000"),
                new BigDecimal("80000"),
                false,
                new PairAnalysis.Components(
                        new BigDecimal("80"),
                        new BigDecimal("70"),
                        new BigDecimal("40"),
                        new BigDecimal("60"),
                        new BigDecimal("55")),
                72,
                SetupStyle.DAY_TRADING,
                null,
                new BigDecimal("78040"),
                new BigDecimal("0.0001"));
    }

    private static PlanContext plan() {
        return new PlanContext(
                PAIR,
                MarketType.FUTURES,
                Direction.LONG,
                EntryType.LIMIT,
                new BigDecimal("77000"),
                new BigDecimal("75000"),
                new BigDecimal("82000"),
                new BigDecimal("1"),
                10,
                null,
                "1h",
                PlanStatus.DRAFT,
                new BigDecimal("72"),
                "DAY_TRADING v1",
                new BigDecimal("2.5"),
                new BigDecimal("2.3"),
                new BigDecimal("0.08"),
                new BigDecimal("70000"),
                List.of(new PlanContext.Warning(WarningType.HIGH_LEVERAGE, WarningSeverity.WARNING, "High leverage")));
    }
}
