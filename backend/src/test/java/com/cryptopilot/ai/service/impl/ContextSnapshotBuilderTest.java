package com.cryptopilot.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.cryptopilot.journal.JournalApi;
import com.cryptopilot.journal.PerformanceDigest;
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
    private final JournalApi journal = mock(JournalApi.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContextSnapshotBuilder builder =
            new ContextSnapshotBuilder(market, plans, journal, entitlements, json, Clock.fixed(NOW, ZoneOffset.UTC));

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
        verifyNoInteractions(market, plans, journal, entitlements);
    }

    @Test
    void BR51_onPremiumWithClosedTrades_theSnapshotCarriesTheJournalsAggregatesAndLearningMetrics() {
        when(journal.performanceDigest(USER)).thenReturn(digest());

        ContextSnapshot premium = builder.build(USER, new AiContextSelection(null, null, null, null, true));

        verify(entitlements).requireFeature(USER, Feature.AI_PERFORMANCE_CONTEXT);
        assertThat(premium.performance().available()).isTrue();
        assertThat(premium.performance().message()).isNull();
        assertThat(premium.performance().summary()).isEqualTo(digest());
        JsonNode summary =
                json.readTree(builder.toJson(premium)).path("performance").path("summary");
        assertThat(summary.path("trades").asInt()).isEqualTo(12);
        assertThat(summary.path("winRate").asString()).isEqualTo("0.58333333");
        assertThat(summary.path("averageRealizedR").asString()).isEqualTo("0.42");
        assertThat(summary.path("averageEntryDeviationR").asString()).isEqualTo("0.08");
        assertThat(summary.path("averageMaeR").asString()).isEqualTo("-0.61");
        assertThat(summary.path("averageMfeR").asString()).isEqualTo("1.37");
    }

    @Test
    void BR51_onPremiumWithoutAClosedTrade_theSummarySaysThereIsNothingToReview() {
        when(journal.performanceDigest(USER))
                .thenReturn(new PerformanceDigest(
                        0,
                        0,
                        0,
                        0,
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null));

        ContextSnapshot premium = builder.build(USER, new AiContextSelection(null, null, null, null, true));

        assertThat(premium.performance()).isEqualTo(PerformanceContext.NO_CLOSED_TRADES);
        assertThat(premium.performance().available()).isFalse();
        assertThat(premium.performance().summary()).isNull();
        assertThat(premium.performance().message()).contains("no closed simulated trade");
    }

    @Test
    void BR62_belowPremium_thePerformanceSummaryIsRefusedWithMsg29_andTheJournalIsNotRead() {
        doThrow(new BusinessException(ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "PREMIUM only"))
                .when(entitlements)
                .requireFeature(USER, Feature.AI_PERFORMANCE_CONTEXT);

        assertThatThrownBy(() -> builder.build(USER, new AiContextSelection(null, null, null, null, true)))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.PLAN_FEATURE_NOT_INCLUDED));
        verify(journal, never()).performanceDigest(any());
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
        when(journal.performanceDigest(USER)).thenReturn(digest());

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
        assertThat(root.path("performance").path("summary").path("trades").asInt())
                .isEqualTo(12);
        JsonNode summary = root.path("performance").path("summary");
        assertThat(summary.properties())
                .as("aggregates only: no trade list, no nested record")
                .allSatisfy(field -> assertThat(field.getValue().isContainer()).isFalse());
    }

    private static PerformanceDigest digest() {
        return new PerformanceDigest(
                12,
                7,
                5,
                0,
                new BigDecimal("0.58333333"),
                new BigDecimal("184.20"),
                new BigDecimal("12.40"),
                new BigDecimal("-1.10"),
                1,
                new BigDecimal("1.9"),
                new BigDecimal("0.42"),
                11,
                new BigDecimal("0.08"),
                new BigDecimal("0.88"),
                new BigDecimal("-0.61"),
                new BigDecimal("1.37"));
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
