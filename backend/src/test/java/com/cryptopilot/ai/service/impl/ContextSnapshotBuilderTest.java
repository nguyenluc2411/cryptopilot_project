package com.cryptopilot.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.ai.AssistantArticle;
import com.cryptopilot.ai.AssistantNews;
import com.cryptopilot.ai.AssistantNewsSource;
import com.cryptopilot.ai.model.AiContextSelection;
import com.cryptopilot.ai.model.ContextSnapshot;
import com.cryptopilot.ai.model.NewsContext;
import com.cryptopilot.ai.model.PerformanceContext;
import com.cryptopilot.ai.model.TraderContext;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.journal.FillLearning;
import com.cryptopilot.journal.FillLearningReader;
import com.cryptopilot.journal.JournalApi;
import com.cryptopilot.journal.PerformanceDigest;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairAnalysis;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.CapReason;
import com.cryptopilot.market.model.enums.DominantSide;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.ScoredSide;
import com.cryptopilot.market.model.enums.SetupStyle;
import com.cryptopilot.news.config.NewsAssistantProperties;
import com.cryptopilot.trading.PlanContext;
import com.cryptopilot.trading.TradingPlanApi;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.MarginMode;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.PositionStatus;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import com.cryptopilot.user.TradingProfile;
import com.cryptopilot.user.UserApi;
import com.cryptopilot.user.model.enums.RiskProfile;
import com.cryptopilot.user.model.enums.TradingStyle;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamWriteFeature;
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
    private final FillLearningReader learning = mock(FillLearningReader.class);
    private final EntitlementApi entitlements = mock(EntitlementApi.class);
    private final AssistantNewsSource news = mock(AssistantNewsSource.class);
    private final UserApi users = mock(UserApi.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContextSnapshotBuilder builder = new ContextSnapshotBuilder(
            market, plans, journal, learning, news, users, entitlements, json, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void noNewsByDefault() {
        when(news.recentNews(any())).thenReturn(AssistantNews.NONE);
    }

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

    /** Version 3 (D-146): the stored market part names the side the score was computed for and why it is capped. */
    @Test
    void D146_theStoredSnapshot_carriesTheScoredSideAndTheCapReason_besideTheDominantSide() {
        when(market.pairAnalysis(PAIR, MarketType.FUTURES, "1h", USER)).thenReturn(Optional.of(analysis()));

        JsonNode root = json.readTree(builder.toJson(
                builder.build(USER, new AiContextSelection(PAIR, MarketType.FUTURES, "1h", null, false))));

        assertThat(root.path("version").asInt()).isEqualTo(ContextSnapshot.VERSION);
        assertThat(root.path("market").path("scoredSide").asString()).isEqualTo("SHORT");
        assertThat(root.path("market").path("capReason").asString()).isEqualTo("LOW_ROOM");
        assertThat(root.path("market").path("dominantSide").asString()).isEqualTo("NEUTRAL");
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
        verifyNoInteractions(market, plans, journal, entitlements, news, users);
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

    @Test
    void BR51_onPremium_eachRecentTradeCarriesItsDeviationScoresAndExcursions() {
        UUID btc = UUID.randomUUID();
        when(journal.performanceDigest(USER)).thenReturn(digest());
        when(learning.latestClosed(USER, ContextSnapshotBuilder.RECENT_TRADES))
                .thenReturn(List.of(trade(
                        btc,
                        new BigDecimal("0.12500000"),
                        new BigDecimal("72.50"),
                        new BigDecimal("64.00"),
                        new BigDecimal("1.90499999"),
                        new BigDecimal("-0.61500000"),
                        new BigDecimal("2.33333333"))));
        when(market.pairListings(Set.of(btc))).thenReturn(List.of(new PairListing(btc, "BTCUSDT", true, true)));

        ContextSnapshot premium = builder.build(USER, new AiContextSelection(null, null, null, null, true));

        assertThat(premium.performance().recentTrades())
                .containsExactly(new PerformanceContext.TradeLearning(
                        "BTCUSDT",
                        MarketType.FUTURES,
                        Direction.SHORT,
                        new BigDecimal("0.13"),
                        new BigDecimal("72.50"),
                        new BigDecimal("64.00"),
                        new BigDecimal("1.90"),
                        new BigDecimal("-0.62"),
                        new BigDecimal("2.33")));
        JsonNode trade = json.readTree(builder.toJson(premium))
                .path("performance")
                .path("recentTrades")
                .get(0);
        assertThat(trade.propertyNames())
                .containsExactlyInAnyOrder(
                        "symbol",
                        "market",
                        "direction",
                        "entryDeviationR",
                        "setupScoreAtPlan",
                        "setupScoreAtFill",
                        "rMultiple",
                        "maeR",
                        "mfeR");
        assertThat(trade.path("symbol").asString()).isEqualTo("BTCUSDT");
        assertThat(trade.path("maeR").decimalValue()).isEqualByComparingTo("-0.62");
    }

    @Test
    void BR51_theRecentTrades_carryNoIdPriceOrAmount() {
        UUID btc = UUID.randomUUID();
        FillLearning trade = trade(
                btc,
                new BigDecimal("0.1"),
                new BigDecimal("70"),
                new BigDecimal("60"),
                new BigDecimal("1.9"),
                new BigDecimal("0.5"),
                new BigDecimal("2.5"));
        when(journal.performanceDigest(USER)).thenReturn(digest());
        when(learning.latestClosed(USER, ContextSnapshotBuilder.RECENT_TRADES)).thenReturn(List.of(trade));
        when(market.pairListings(any())).thenReturn(List.of(new PairListing(btc, "BTCUSDT", true, true)));

        String stored = builder.toJson(builder.build(USER, new AiContextSelection(null, null, null, null, true)));

        assertThat(stored)
                .doesNotContain(trade.journalId().toString())
                .doesNotContain(trade.planId().toString())
                .doesNotContain(btc.toString())
                .doesNotContain(USER.toString())
                .doesNotContain(trade.entryPrice().toPlainString())
                .doesNotContain(trade.plannedEntryPrice().toPlainString())
                .doesNotContain(trade.maxAdversePrice().toPlainString())
                .doesNotContain(trade.maxFavorablePrice().toPlainString())
                .doesNotContain(trade.riskAmount().toPlainString())
                .doesNotContain(trade.netPnl().toPlainString());
        assertThat(fieldNames(json.readTree(stored)))
                .doesNotContain(
                        "journalId",
                        "planId",
                        "pairId",
                        "entryPrice",
                        "plannedEntryPrice",
                        "maxAdversePrice",
                        "maxFavorablePrice",
                        "riskAmount",
                        "netPnl",
                        "entryTime",
                        "exitTime",
                        "entryLiquidity",
                        "setupScorePreset",
                        "indicators");
    }

    @Test
    void T074b_aSummaryWithoutTradeList_carriesAnEmptyOne_andAGivenListIsCopied() {
        assertThat(new PerformanceContext(true, null, digest(), null).recentTrades())
                .isEmpty();
        List<PerformanceContext.TradeLearning> given = new ArrayList<>();
        PerformanceContext context = new PerformanceContext(true, null, digest(), given);
        given.add(new PerformanceContext.TradeLearning(null, null, null, null, null, null, null, null, null));
        assertThat(context.recentTrades()).isEmpty();
    }

    @Test
    void T074b_aTradeBeforeTheLearningData_isSentWithNullFields() {
        UUID btc = UUID.randomUUID();
        when(journal.performanceDigest(USER)).thenReturn(digest());
        when(learning.latestClosed(USER, ContextSnapshotBuilder.RECENT_TRADES))
                .thenReturn(List.of(trade(btc, null, null, null, null, null, null)));
        when(market.pairListings(any())).thenReturn(List.of(new PairListing(btc, "BTCUSDT", true, true)));

        ContextSnapshot premium = builder.build(USER, new AiContextSelection(null, null, null, null, true));

        assertThat(premium.performance().recentTrades())
                .containsExactly(new PerformanceContext.TradeLearning(
                        "BTCUSDT", MarketType.FUTURES, Direction.SHORT, null, null, null, null, null, null));
    }

    @Test
    void T074b_anUnknownPair_givesANullSymbol_andTheQuestionStillBuilds() {
        when(journal.performanceDigest(USER)).thenReturn(digest());
        when(learning.latestClosed(USER, ContextSnapshotBuilder.RECENT_TRADES))
                .thenReturn(List.of(trade(UUID.randomUUID(), null, null, null, BigDecimal.ONE, null, null)));
        when(market.pairListings(any())).thenReturn(List.of());

        ContextSnapshot premium = builder.build(USER, new AiContextSelection(null, null, null, null, true));

        assertThat(premium.performance().recentTrades()).singleElement().satisfies(trade -> {
            assertThat(trade.symbol()).isNull();
            assertThat(trade.rMultiple()).isEqualTo(new BigDecimal("1.00"));
        });
        assertThat(builder.toJson(premium)).isNotNull();
    }

    @Test
    void T074b_eachPairIsLookedUpOnce() {
        UUID btc = UUID.randomUUID();
        UUID eth = UUID.randomUUID();
        when(journal.performanceDigest(USER)).thenReturn(digest());
        when(learning.latestClosed(USER, ContextSnapshotBuilder.RECENT_TRADES))
                .thenReturn(List.of(
                        trade(btc, null, null, null, null, null, null),
                        trade(eth, null, null, null, null, null, null),
                        trade(btc, null, null, null, null, null, null)));
        when(market.pairListings(any()))
                .thenReturn(List.of(
                        new PairListing(btc, "BTCUSDT", true, true), new PairListing(eth, "ETHUSDT", true, false)));

        ContextSnapshot premium = builder.build(USER, new AiContextSelection(null, null, null, null, true));

        verify(market, times(1)).pairListings(any());
        verify(market).pairListings(Set.of(btc, eth));
        assertThat(premium.performance().recentTrades())
                .extracting(PerformanceContext.TradeLearning::symbol)
                .containsExactly("BTCUSDT", "ETHUSDT", "BTCUSDT");
    }

    @Test
    void T074b_withoutAClosedTrade_theTradesAreNotRead() {
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

        assertThat(premium.performance().recentTrades()).isEmpty();
        verifyNoInteractions(learning);
        verify(market, never()).pairListings(any());
    }

    @Test
    void BR62_belowPremium_theTradesAreNotRead() {
        doThrow(new BusinessException(ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "PREMIUM only"))
                .when(entitlements)
                .requireFeature(USER, Feature.AI_PERFORMANCE_CONTEXT);

        assertThatThrownBy(() -> builder.build(USER, new AiContextSelection(null, null, null, null, true)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(learning);
        verify(market, never()).pairListings(any());
    }

    @Test
    void T074b_theTradesAreAskedForWithTheContextLimit() {
        when(journal.performanceDigest(USER)).thenReturn(digest());

        ContextSnapshot premium = builder.build(USER, new AiContextSelection(null, null, null, null, true));

        assertThat(ContextSnapshotBuilder.RECENT_TRADES).isEqualTo(10);
        verify(learning).latestClosed(USER, 10);
        assertThat(premium.performance().recentTrades()).isEmpty();
        verify(market, never()).pairListings(any());
    }

    /** A closed simulated Futures SHORT with these learning values, and prices and amounts that must not be sent. */
    private static FillLearning trade(
            UUID pairId,
            BigDecimal deviation,
            BigDecimal atPlan,
            BigDecimal atFill,
            BigDecimal rMultiple,
            BigDecimal mae,
            BigDecimal mfe) {
        return new FillLearning(
                UUID.randomUUID(),
                UUID.randomUUID(),
                pairId,
                MarketType.FUTURES,
                Direction.SHORT,
                PositionStatus.CLOSED,
                NOW.minusSeconds(7200),
                NOW.minusSeconds(3600),
                new BigDecimal("77123.45"),
                new BigDecimal("77011.22"),
                deviation,
                LiquidityRole.TAKER,
                new BigDecimal("53.17"),
                "1h",
                "DAY_TRADING v1",
                atPlan,
                atFill,
                Boolean.FALSE,
                null,
                new BigDecimal("78345.67"),
                new BigDecimal("75432.10"),
                new BigDecimal("101.29"),
                rMultiple,
                mae,
                mfe);
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
        List<String> names = new ArrayList<>();
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

    /** Version 4 (T-074): a question about a pair reads the news about its coin and the market, numbered to cite. */
    @Test
    void T074_aPairQuestion_carriesTheCoinNewsThenTheMarketNews_numberedFromOne_withTheTimeOfTheLastRead() {
        Instant crawled = NOW.minusSeconds(420);
        AssistantArticle coin = new AssistantArticle(
                "ETF Bitcoin bị rút ròng",
                "CoinDesk",
                NOW.minusSeconds(3600),
                "Quỹ ETF bị rút 635 triệu USD.",
                "BEARISH",
                "https://coindesk.example/etf");
        AssistantArticle marketWide = new AssistantArticle(
                "Sàn bị hack 6,3 tỷ USD",
                "Cointelegraph",
                NOW.minusSeconds(7200),
                null,
                null,
                "https://ct.example/hack");
        when(news.recentNews(PAIR)).thenReturn(new AssistantNews("BTC", crawled, List.of(coin), List.of(marketWide)));
        when(market.pairAnalysis(PAIR, MarketType.SPOT, null, USER)).thenReturn(Optional.of(analysis()));

        ContextSnapshot snapshot = builder.build(USER, new AiContextSelection(PAIR, null, null, null, false));

        assertThat(snapshot.version()).isEqualTo(ContextSnapshot.VERSION);
        assertThat(snapshot.news().coin()).isEqualTo("BTC");
        assertThat(snapshot.news().checkedUpTo()).isEqualTo(crawled);
        assertThat(snapshot.news().articles())
                .containsExactly(
                        new NewsContext.NewsItem(
                                1,
                                NewsContext.About.COIN,
                                "ETF Bitcoin bị rút ròng",
                                "CoinDesk",
                                coin.publishedAt(),
                                "Quỹ ETF bị rút 635 triệu USD.",
                                "BEARISH",
                                "https://coindesk.example/etf"),
                        new NewsContext.NewsItem(
                                2,
                                NewsContext.About.MARKET,
                                "Sàn bị hack 6,3 tỷ USD",
                                "Cointelegraph",
                                marketWide.publishedAt(),
                                null,
                                null,
                                "https://ct.example/hack"));
        JsonNode root = json.readTree(builder.toJson(snapshot));
        assertThat(root.path("news").path("articles").get(0).path("ref").asInt())
                .isEqualTo(1);
        assertThat(root.path("news").path("articles").get(1).path("url").asString())
                .isEqualTo("https://ct.example/hack");
    }

    @Test
    void T074_aPlanQuestion_readsTheNewsOfThePlansPair_andAPairWithoutNews_saysWhenTheOutletsWereRead() {
        when(plans.planContext(USER, PLAN)).thenReturn(plan());
        when(market.pairAnalysis(PAIR, MarketType.FUTURES, "1h", USER)).thenReturn(Optional.of(analysis()));
        when(news.recentNews(PAIR)).thenReturn(new AssistantNews("BTC", NOW, List.of(), List.of()));

        ContextSnapshot snapshot = builder.build(USER, new AiContextSelection(null, null, null, PLAN, false));

        verify(news).recentNews(PAIR);
        assertThat(snapshot.news().articles()).isEmpty();
        assertThat(snapshot.news().checkedUpTo()).isEqualTo(NOW);
    }

    @Test
    void T074_aQuestionWithAnythingChosen_carriesTheTradersStyle_butNoNewsWithoutAPair() {
        when(journal.performanceDigest(USER)).thenReturn(digest());
        when(users.tradingProfileOf(USER))
                .thenReturn(Optional.of(new TradingProfile(TradingStyle.SWING, RiskProfile.BALANCED)));

        ContextSnapshot snapshot = builder.build(USER, new AiContextSelection(null, null, null, null, true));

        assertThat(snapshot.trader()).isEqualTo(new TraderContext("SWING", "BALANCED"));
        assertThat(snapshot.news()).isNull();
        verifyNoInteractions(news);
    }

    @Test
    void T074_aStyleTheProfileHasNotSet_isSentAsNull_andNoProfileSendsNoTrader() {
        when(market.pairAnalysis(PAIR, MarketType.SPOT, null, USER)).thenReturn(Optional.of(analysis()));
        when(users.tradingProfileOf(USER)).thenReturn(Optional.of(new TradingProfile(null, RiskProfile.CONSERVATIVE)));

        assertThat(builder.build(USER, new AiContextSelection(PAIR, null, null, null, false))
                        .trader())
                .isEqualTo(new TraderContext(null, "CONSERVATIVE"));

        when(users.tradingProfileOf(USER)).thenReturn(Optional.empty());
        assertThat(builder.build(USER, new AiContextSelection(PAIR, null, null, null, false))
                        .trader())
                .isNull();
    }

    /**
     * NSF-16 (F1): the largest context the news limits allow, with the recent trades the performance summary carries
     * (T-074b), still fits the AI Service's 20,000 characters.
     */
    @Test
    void NSF16_theWorstCaseContextAtTheConfiguredLimits_isWithin20000Characters() {
        int atTheLimits = worstCaseContextLength(
                NewsAssistantProperties.MAX_ARTICLES, 0, NewsAssistantProperties.MAX_SUMMARY_CHARS);
        int atTheDefaults = worstCaseContextLength(5, 3, 400);

        // Measured with the 10 recent trades: 19,213 at the limits, 17,619 at the defaults (16,866 / 15,272 without).
        assertThat(atTheLimits).as("worst case at the limits").isLessThanOrEqualTo(20_000);
        assertThat(atTheDefaults).as("worst case at the defaults").isLessThanOrEqualTo(atTheLimits);
    }

    /**
     * The context as the application writes it (decimals as strings) with every part at its largest: the market with
     * every indicator, a plan with every warning, PREMIUM performance with {@link ContextSnapshotBuilder#RECENT_TRADES}
     * trades of a 20-character symbol at the widest values, the trader, and the articles at their longest
     * (headline 200 and link 500, as {@code NewsForAssistant} cuts them; outlet 100, the column's length).
     */
    private int worstCaseContextLength(int coinArticles, int marketArticles, int summaryChars) {
        JsonMapper app = JsonMapper.builder()
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .withConfigOverride(
                        BigDecimal.class,
                        override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
                .build();
        ContextSnapshotBuilder worst = new ContextSnapshotBuilder(
                market, plans, journal, learning, news, users, entitlements, app, Clock.fixed(NOW, ZoneOffset.UTC));
        BigDecimal wide = new BigDecimal("123456789.123456789012");
        PairAnalysis full = new PairAnalysis(
                "1000SATSUSDT",
                MarketType.FUTURES,
                "1h",
                NOW,
                wide,
                new PairAnalysis.Indicators(wide, wide, wide, wide, wide, wide, wide, wide, wide, wide, wide, wide),
                wide,
                wide,
                true,
                new PairAnalysis.Components(wide, wide, wide, wide, wide),
                100,
                SetupStyle.DAY_TRADING,
                DominantSide.NEUTRAL,
                ScoredSide.SHORT,
                CapReason.LOW_ROOM,
                wide,
                wide);
        List<PlanContext.Warning> warnings = Arrays.stream(WarningType.values())
                .map(type -> new PlanContext.Warning(type, type.severity(), "x".repeat(200)))
                .toList();
        PlanContext plan = new PlanContext(
                PAIR,
                MarketType.FUTURES,
                Direction.LONG,
                EntryType.LIMIT,
                wide,
                wide,
                wide,
                wide,
                125,
                MarginMode.ISOLATED,
                "1h",
                PlanStatus.DRAFT,
                wide,
                "DAY_TRADING v1",
                wide,
                wide,
                wide,
                wide,
                warnings);
        AssistantArticle longest = new AssistantArticle(
                "h".repeat(200),
                "o".repeat(100),
                NOW,
                "s".repeat(summaryChars),
                "NEUTRAL",
                "https://e.example/" + "u".repeat(500 - 18));
        when(plans.planContext(USER, PLAN)).thenReturn(plan);
        when(market.pairAnalysis(PAIR, MarketType.FUTURES, "1h", USER)).thenReturn(Optional.of(full));
        when(journal.performanceDigest(USER)).thenReturn(digest());
        UUID tradedPair = UUID.randomUUID();
        // A setup score is numeric(5,2) checked to 0..100, so 100.00 is its widest value.
        BigDecimal widestScore = new BigDecimal("100.00");
        when(learning.latestClosed(USER, ContextSnapshotBuilder.RECENT_TRADES))
                .thenReturn(Collections.nCopies(
                        ContextSnapshotBuilder.RECENT_TRADES,
                        trade(tradedPair, wide, widestScore, widestScore, wide, wide, wide)));
        when(market.pairListings(any())).thenReturn(List.of(new PairListing(tradedPair, "S".repeat(20), true, true)));
        when(users.tradingProfileOf(USER))
                .thenReturn(Optional.of(new TradingProfile(TradingStyle.POSITION, RiskProfile.CONSERVATIVE)));
        when(news.recentNews(PAIR))
                .thenReturn(new AssistantNews(
                        "1000SATS",
                        NOW,
                        Collections.nCopies(coinArticles, longest),
                        Collections.nCopies(marketArticles, longest)));

        String context = worst.toJson(worst.build(USER, new AiContextSelection(null, null, null, PLAN, true)));
        return context.length();
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
                DominantSide.NEUTRAL,
                ScoredSide.SHORT,
                CapReason.LOW_ROOM,
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
