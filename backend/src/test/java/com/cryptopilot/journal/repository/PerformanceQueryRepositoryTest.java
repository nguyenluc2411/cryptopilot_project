package com.cryptopilot.journal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.journal.TradeRow;
import com.cryptopilot.journal.model.EquityPoint;
import com.cryptopilot.journal.model.PerformanceCounts;
import com.cryptopilot.journal.model.PerformanceDetail;
import com.cryptopilot.journal.model.PerformanceFilter;
import com.cryptopilot.journal.model.PerformanceGroup;
import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The statistics of UC-25 on fixed datasets over the real schema, every expected value computed by hand: BR-42, the
 * net P/L of D-97 and the worked examples of TECHNICAL_DESIGN 7.8.
 *
 * <p>Rule: UC-25; BR-38, BR-41, BR-42, BR-70; D-97, D-111, D-124.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class PerformanceQueryRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant TO = T0.plusSeconds(90L * 24 * 3600);
    private static final UUID BREAKOUT = UUID.fromString("019b76da-a800-7001-8000-000000000001");
    private static final UUID SWING = UUID.fromString("019b76da-a800-7001-8000-000000000002");

    @Autowired
    private PerformanceQueryRepository statistics;

    @Autowired
    private JdbcClient sql;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID btc;
    private UUID eth;
    private UUID trader;

    @BeforeEach
    void setUp() {
        btc = pair("PA");
        eth = pair("PB");
        trader = account();
    }

    @AfterEach
    void clear() {
        for (UUID account : accounts) {
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from trading_plan where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        new MarketTestData(sql, Instant.now()).remove(btc);
        new MarketTestData(sql, Instant.now()).remove(eth);
    }

    @Test
    void BR42_noTrades_countsZeroAndHasNoRatios() {
        PerformanceDetail detail = statistics.detail(all());

        PerformanceCounts counts = detail.counts();
        assertThat(counts.trades()).isZero();
        assertThat(counts.wins()).isZero();
        assertThat(counts.winRate()).isNull();
        assertThat(counts.totalNetPnl()).isEqualByComparingTo("0");
        assertThat(detail.averageWin()).isNull();
        assertThat(detail.averageLoss()).isNull();
        assertThat(detail.profitFactor()).isNull();
        assertThat(detail.averageRealizedR()).isNull();
        assertThat(detail.largestWin()).isNull();
        assertThat(detail.largestLoss()).isNull();
        assertThat(detail.maxDrawdown()).isNull();
        assertThat(detail.maxConsecutiveLosses()).isZero();
        assertThat(detail.tradesWithR()).isZero();
        assertThat(counts.totalFees()).isEqualByComparingTo("0");
        assertThat(counts.totalFunding()).isEqualByComparingTo("0");
        assertThat(statistics.byPair(all())).isEmpty();
        assertThat(statistics.equityCurve(all(), "Asia/Ho_Chi_Minh")).isEmpty();
    }

    @Test
    void BR42_oneTrade_isItsOwnAverage() {
        closed(0).amounts("12.5", "0.5").stop("95").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().trades()).isEqualTo(1);
        assertThat(detail.counts().winRate()).isEqualByComparingTo("1");
        assertThat(detail.counts().totalNetPnl()).isEqualByComparingTo("12");
        assertThat(detail.averageWin()).isEqualByComparingTo("12");
        assertThat(detail.largestWin()).isEqualByComparingTo("12");
        // risk |100 - 95| x 1 = 5
        assertThat(detail.averageRealizedR()).isEqualByComparingTo("2.4");
        assertThat(detail.maxDrawdown()).isEqualByComparingTo("0");
    }

    @Test
    void BR42_allWins_haveNoProfitFactorAndNoDrawdown() {
        closed(0).amounts("10", "0").insert(sql);
        closed(1).amounts("30", "0").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().wins()).isEqualTo(2);
        assertThat(detail.counts().winRate()).isEqualByComparingTo("1");
        assertThat(detail.counts().totalNetPnl()).isEqualByComparingTo("40");
        assertThat(detail.profitFactor()).isNull();
        assertThat(detail.averageWin()).isEqualByComparingTo("20");
        assertThat(detail.averageLoss()).isNull();
        assertThat(detail.largestWin()).isEqualByComparingTo("30");
        assertThat(detail.largestLoss()).isNull();
        assertThat(detail.maxConsecutiveLosses()).isZero();
        assertThat(detail.maxDrawdown()).isEqualByComparingTo("0");
    }

    @Test
    void BR42_allLosses_haveAZeroProfitFactorAndDrawDownFromZero() {
        closed(0).amounts("-10", "0").insert(sql);
        closed(1).amounts("-30", "0").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().losses()).isEqualTo(2);
        assertThat(detail.counts().winRate()).isEqualByComparingTo("0");
        assertThat(detail.profitFactor()).isEqualByComparingTo("0");
        assertThat(detail.averageLoss()).isEqualByComparingTo("-20");
        assertThat(detail.largestLoss()).isEqualByComparingTo("-30");
        assertThat(detail.largestWin()).isNull();
        assertThat(detail.maxConsecutiveLosses()).isEqualTo(2);
        assertThat(detail.maxDrawdown()).isEqualByComparingTo("40");
    }

    /** Net +10, -5, -20, +8, -3, +40: cumulative 10, 5, -15, -7, -10, 30; the peak 10 falls to -15. */
    @Test
    void BR42_mixedSequence_matchesTheHandComputedMetrics() {
        String[] nets = {"10", "-5", "-20", "8", "-3", "40"};
        for (int day = 0; day < nets.length; day++) {
            closed(day).amounts(nets[day], "0").insert(sql);
        }

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().trades()).isEqualTo(6);
        assertThat(detail.counts().wins()).isEqualTo(3);
        assertThat(detail.counts().losses()).isEqualTo(3);
        assertThat(detail.counts().winRate()).isEqualByComparingTo("0.5");
        assertThat(detail.counts().totalNetPnl()).isEqualByComparingTo("30");
        assertThat(detail.averageWin()).isEqualByComparingTo("19.33333333");
        assertThat(detail.averageLoss()).isEqualByComparingTo("-9.33333333");
        // gross profit 58 / gross loss 28
        assertThat(detail.profitFactor()).isEqualByComparingTo("2.07142857");
        assertThat(detail.largestWin()).isEqualByComparingTo("40");
        assertThat(detail.largestLoss()).isEqualByComparingTo("-20");
        assertThat(detail.maxConsecutiveLosses()).isEqualTo(2);
        assertThat(detail.maxDrawdown()).isEqualByComparingTo("25");
    }

    /** Exit order decides, not insertion order: the curve is read by exit time. */
    @Test
    void BR42_drawdown_followsExitOrder() {
        closed(2).amounts("-40", "0").insert(sql);
        closed(0).amounts("50", "0").insert(sql);
        closed(1).amounts("-5", "0").insert(sql);

        // cumulative 50, 45, 5
        assertThat(statistics.detail(all()).maxDrawdown()).isEqualByComparingTo("45");
    }

    @Test
    void BR42_breakeven_isExcludedFromTheWinRateAndEndsALosingRun() {
        closed(0).amounts("-1", "0").insert(sql);
        closed(1).amounts("0.21", "0.21").insert(sql);
        closed(2).amounts("-1", "0").insert(sql);
        closed(3).amounts("3", "0").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().breakeven()).isEqualTo(1);
        // 1 win / (1 win + 2 losses)
        assertThat(detail.counts().winRate()).isEqualByComparingTo("0.33333333");
        assertThat(detail.maxConsecutiveLosses()).isEqualTo(1);
    }

    @Test
    void BR42_onlyBreakeven_hasNoWinRateAndNoProfitFactor() {
        closed(0).amounts("1", "1").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().breakeven()).isEqualTo(1);
        assertThat(detail.counts().winRate()).isNull();
        assertThat(detail.profitFactor()).isNull();
    }

    /** TECHNICAL_DESIGN 7.8: a liquidation of realized -19.26 with fees of 0.76296 nets -20.02296. */
    @Test
    void BR42_liquidation_isALossWithoutAStopLossForR() {
        closed(0)
                .futures("LONG")
                .entry("100", "2")
                .amounts("-19.26", "0.76296")
                .reason("LIQUIDATION")
                .insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().losses()).isEqualTo(1);
        assertThat(detail.counts().totalNetPnl()).isEqualByComparingTo("-20.02296");
        assertThat(detail.tradesWithR()).isZero();
        assertThat(detail.averageRealizedR()).isNull();
    }

    /** D-138 (6): the result is the net alone, so a liquidation that nets above 0 is a win. */
    @Test
    void BR42_liquidationWithANetAboveZero_isAWin() {
        closed(0).futures("LONG").amounts("5", "1").reason("LIQUIDATION").insert(sql);

        PerformanceCounts counts = statistics.counts(all());

        assertThat(counts.wins()).isEqualTo(1);
        assertThat(counts.losses()).isZero();
        assertThat(counts.totalNetPnl()).isEqualByComparingTo("4");
    }

    /** D-97: Spot 10 - 0.21 = 9.79; Futures 30 - 0.086 - 0.0202 = 29.8938 (TECHNICAL_DESIGN 7.8). */
    @Test
    void D97_spotAndFutures_useTheNetOfFeesAndSignedFunding() {
        closed(0).amounts("10", "0.21").insert(sql);
        closed(1).futures("LONG").amounts("30", "0.086").funding("-0.0202").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().totalNetPnl()).isEqualByComparingTo("39.6838");
        assertThat(detail.counts().totalFees()).isEqualByComparingTo("0.296");
        assertThat(detail.counts().totalFunding()).isEqualByComparingTo("-0.0202");
    }

    /** A gross win that its fees turn into a loss is a loss (BR-38, BR-42). */
    @Test
    void BR42_result_isDecidedByTheNetNotTheGross() {
        closed(0).amounts("1", "1.5").insert(sql);
        closed(1).futures("SHORT").amounts("-1", "0").funding("2").insert(sql);

        PerformanceCounts counts = statistics.counts(all());

        assertThat(counts.wins()).isEqualTo(1);
        assertThat(counts.losses()).isEqualTo(1);
    }

    @Test
    void D139_closedTradeWithoutAFee_isCountedWithFeeZero() {
        closed(0).amounts("10", "1").insert(sql);
        closed(1).amounts("-4", "0").noFee().insert(sql);

        PerformanceCounts counts = statistics.counts(all());

        assertThat(counts.trades()).isEqualTo(2);
        assertThat(counts.losses()).isEqualTo(1);
        assertThat(counts.totalNetPnl()).isEqualByComparingTo("5");
        assertThat(counts.totalFees()).isEqualByComparingTo("1");
        assertThat(statistics.detail(all()).largestLoss()).isEqualByComparingTo("-4");
    }

    @Test
    void D97_incompleteFunding_isCountedAsProvisional() {
        closed(0)
                .futures("LONG")
                .amounts("10", "0")
                .funding("-1")
                .fundingIncomplete()
                .insert(sql);
        closed(1).amounts("10", "0").insert(sql);

        assertThat(statistics.counts(all()).provisionalTrades()).isEqualTo(1);
    }

    /** TECHNICAL_DESIGN 7.8: the LONG and SHORT worked examples, R, MAE, MFE and the expectancy over both. */
    @Test
    void BR70_workedExamples_reproduceRAndExcursions() {
        closed(0)
                .futures("LONG")
                .entry("100", "2")
                .amounts("30", "0.086")
                .stop("95")
                .take("115")
                .risk("10")
                .excursions("97", "115")
                .insert(sql);
        closed(1)
                .futures("SHORT")
                .entry("100", "2")
                .amounts("-10", "0.205")
                .stop("105")
                .risk("10")
                .excursions("105", "98")
                .insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        // R 2.9914 and -1.0205
        assertThat(detail.averageRealizedR()).isEqualByComparingTo("0.98545");
        assertThat(detail.tradesWithR()).isEqualTo(2);
        // MAE 0.6R and 1.0R, MFE 3.0R and 0.4R
        assertThat(detail.averageMaeR()).isEqualByComparingTo("0.8");
        assertThat(detail.averageMfeR()).isEqualByComparingTo("1.7");
    }

    /** TECHNICAL_DESIGN 7.8: deviations 0.5, -0.1 and 0.2. */
    @Test
    void BR70_entryDeviations_giveTheCostOfLateEntry() {
        closed(0).deviation("0.5").insert(sql);
        closed(1).deviation("-0.1").insert(sql);
        closed(2).deviation("0.2").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.costOfLateEntryR()).isEqualByComparingTo("0.6");
        assertThat(detail.averageEntryDeviationR()).isEqualByComparingTo("0.2");
    }

    @Test
    void BR70_withoutLearningData_theLearningMetricsAreNull() {
        closed(0).amounts("10", "0").stop("95").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.averageEntryDeviationR()).isNull();
        assertThat(detail.costOfLateEntryR()).isNull();
        assertThat(detail.averageMaeR()).isNull();
        assertThat(detail.averageMfeR()).isNull();
    }

    @Test
    void BR42_tradeWithoutStopLoss_isLeftOutOfR() {
        closed(0).amounts("10", "0").stop("95").insert(sql);
        closed(1).amounts("90", "0").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.tradesWithR()).isEqualTo(1);
        assertThat(detail.averageRealizedR()).isEqualByComparingTo("2");
    }

    /** The risk is 0, and R is left out rather than divided by it; the trade itself still counts. */
    @Test
    void BR42_stopLossAtTheEntry_isLeftOutOfR() {
        closed(0).amounts("10", "0").stop("100").take("115").insert(sql);

        PerformanceDetail detail = statistics.detail(all());

        assertThat(detail.counts().trades()).isEqualTo(1);
        assertThat(detail.tradesWithR()).isZero();
        assertThat(detail.averageRealizedR()).isNull();
        assertThat(detail.averagePlannedRiskReward()).isNull();
    }

    @Test
    void D124_movedStop_keepsTheOriginalRisk() {
        closed(0).amounts("10", "0").stop("95").movedStop("99").insert(sql);

        assertThat(statistics.detail(all()).averageRealizedR()).isEqualByComparingTo("2");
    }

    /** The planned risk is the base of R; a fill that gapped past the entry does not change it (TD 7.8). */
    @Test
    void BR42_plannedRisk_winsOverTheRiskAtTheFill() {
        closed(0).entry("101", "1").amounts("10", "0").stop("95").risk("5").insert(sql);

        assertThat(statistics.detail(all()).averageRealizedR()).isEqualByComparingTo("2");
    }

    @Test
    void BR42_plannedRiskReward_isTheMeanOfTradesWithBothLevels() {
        closed(0).stop("95").take("115").insert(sql); // 15 / 5
        closed(1).stop("90").take("110").insert(sql); // 10 / 10
        closed(2).stop("90").insert(sql); // no take profit
        closed(3).take("110").insert(sql); // no stop loss

        assertThat(statistics.detail(all()).averagePlannedRiskReward()).isEqualByComparingTo("2");
    }

    @Test
    void D124_movedTakeProfit_keepsTheOriginalRiskReward() {
        closed(0).stop("95").take("115").movedTake("130").insert(sql);

        assertThat(statistics.detail(all()).averagePlannedRiskReward()).isEqualByComparingTo("3");
    }

    @Test
    void BR42_plannedRiskReward_usesThePlannedEntryOverTheFill() {
        closed(0).entry("101", "1").plannedEntry("100").stop("95").take("115").insert(sql);

        assertThat(statistics.detail(all()).averagePlannedRiskReward()).isEqualByComparingTo("3");
    }

    @Test
    void UC25_period_isHalfOpenOnTheExit() {
        closed(0).amounts("1", "0").insert(sql);
        TradeRow.closed(trader, btc, T0.minusNanos(1000)).amounts("2", "0").insert(sql);
        TradeRow.closed(trader, btc, TO).amounts("4", "0").insert(sql);

        assertThat(statistics.counts(all()).totalNetPnl()).isEqualByComparingTo("1");
    }

    @Test
    void UC25_withoutFrom_countsFromTheFirstTrade() {
        closed(0).amounts("1", "0").insert(sql);
        TradeRow.closed(trader, btc, T0.minusSeconds(5L * 365 * 24 * 3600))
                .amounts("2", "0")
                .insert(sql);

        PerformanceFilter allTime =
                new PerformanceFilter(trader, null, TO, PerformanceMarket.ALL, PerformanceSource.ALL, null, null);

        assertThat(statistics.counts(allTime).totalNetPnl()).isEqualByComparingTo("3");
        assertThat(statistics.detail(allTime).counts().trades()).isEqualTo(2);
        assertThat(statistics.equityCurve(allTime, "Asia/Ho_Chi_Minh")).hasSize(2);
    }

    @Test
    void UC25_filters_narrowTheTradesCounted() {
        UUID plan = plan(btc);
        closed(0).amounts("1", "0").simulated(plan).insert(sql);
        closed(1).amounts("2", "0").strategy(BREAKOUT).insert(sql);
        closed(2).futures("LONG").amounts("4", "0").insert(sql);
        TradeRow.closed(trader, eth, T0).amounts("8", "0").insert(sql);

        assertThat(total(filter(PerformanceMarket.ALL, PerformanceSource.ALL, null, null)))
                .isEqualByComparingTo("15");
        assertThat(total(filter(PerformanceMarket.ALL, PerformanceSource.SIMULATED, null, null)))
                .isEqualByComparingTo("1");
        assertThat(total(filter(PerformanceMarket.ALL, PerformanceSource.MANUAL, null, null)))
                .isEqualByComparingTo("14");
        assertThat(total(filter(PerformanceMarket.FUTURES, PerformanceSource.ALL, null, null)))
                .isEqualByComparingTo("4");
        assertThat(total(filter(PerformanceMarket.SPOT, PerformanceSource.ALL, null, null)))
                .isEqualByComparingTo("11");
        assertThat(total(filter(PerformanceMarket.ALL, PerformanceSource.ALL, eth, null)))
                .isEqualByComparingTo("8");
        assertThat(total(filter(PerformanceMarket.ALL, PerformanceSource.ALL, null, BREAKOUT)))
                .isEqualByComparingTo("2");
    }

    @Test
    void UC25_openTradesAndRecordsWithoutAmounts_areNotCounted() {
        closed(0).amounts("1", "0").insert(sql);
        closed(1).noAmounts().insert(sql);
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, market_type, direction, entry_price,
                                                     entry_time, quantity, source, trade_status, created_at,
                                                     updated_at, version)
                        values (gen_random_uuid(), ?, ?, 'SPOT', 'LONG', 100, ?, 1, 'MANUAL', 'OPEN', ?, ?, 0)""").params(trader, btc, ts(T0), ts(T0), ts(T0)).update();

        assertThat(statistics.counts(all()).trades()).isEqualTo(1);
    }

    @Test
    void UC25_anotherTradersTrades_areNeverCounted() {
        closed(0).amounts("1", "0").insert(sql);
        TradeRow.closed(account(), btc, T0).amounts("100", "0").insert(sql);

        assertThat(statistics.counts(all()).totalNetPnl()).isEqualByComparingTo("1");
    }

    @Test
    void UC25_byPair_groupsWithTheHighestTotalFirst() {
        closed(0).amounts("10", "0").stop("95").insert(sql);
        closed(1).amounts("-5", "0").stop("95").insert(sql);
        closed(2).amounts("-5", "0").insert(sql);
        TradeRow.closed(trader, eth, T0).amounts("20", "0").insert(sql);

        List<PerformanceGroup> groups = statistics.byPair(all());

        assertThat(groups).extracting(PerformanceGroup::key).containsExactly(eth.toString(), btc.toString());
        assertThat(groups.get(0).profitFactor()).isNull();
        PerformanceGroup bitcoin = groups.get(1);
        assertThat(bitcoin.counts().trades()).isEqualTo(3);
        assertThat(bitcoin.counts().wins()).isEqualTo(1);
        assertThat(bitcoin.counts().totalNetPnl()).isEqualByComparingTo("0");
        assertThat(bitcoin.profitFactor()).isEqualByComparingTo("1");
        // (2 - 1) / 2, the third trade has no stop loss
        assertThat(bitcoin.averageRealizedR()).isEqualByComparingTo("0.5");
    }

    @Test
    void UC25_byStrategy_keepsTheTradesWithoutOneInTheirOwnGroup() {
        closed(0).amounts("3", "0").insert(sql);
        closed(1).amounts("2", "0").strategy(BREAKOUT).insert(sql);
        closed(2).amounts("1", "0").strategy(SWING).insert(sql);

        assertThat(statistics.byStrategy(all()))
                .extracting(PerformanceGroup::key)
                .containsExactly(null, BREAKOUT.toString(), SWING.toString());
    }

    @Test
    void UC25_byMarket_splitsSpotAndFutures() {
        closed(0).amounts("3", "0").insert(sql);
        closed(1).futures("SHORT").amounts("5", "0").insert(sql);

        assertThat(statistics.byMarket(all())).extracting(PerformanceGroup::key).containsExactly("FUTURES", "SPOT");
    }

    /** 17:30 UTC is already the next day in UTC+7, so the first two exits fall on different days. */
    @Test
    void UC25_equityCurve_addsUpPerDayOfTheZone() {
        Instant day = Instant.parse("2026-10-20T16:30:00Z");
        TradeRow.closed(trader, btc, day).amounts("10", "0").insert(sql);
        TradeRow.closed(trader, btc, day.plusSeconds(3600)).amounts("-4", "0").insert(sql);
        TradeRow.closed(trader, btc, day.plusSeconds(7200)).amounts("-2", "0").insert(sql);
        TradeRow.closed(trader, btc, day.plusSeconds(3 * 24 * 3600))
                .amounts("5", "1")
                .insert(sql);

        List<EquityPoint> curve = statistics.equityCurve(all(), "Asia/Ho_Chi_Minh");

        assertThat(curve).hasSize(3);
        assertPoint(curve.get(0), LocalDate.parse("2026-10-20"), 1, "10", "10");
        assertPoint(curve.get(1), LocalDate.parse("2026-10-21"), 2, "-6", "4");
        assertPoint(curve.get(2), LocalDate.parse("2026-10-23"), 1, "4", "8");
    }

    private static void assertPoint(EquityPoint point, LocalDate day, long trades, String net, String cumulative) {
        assertThat(point.day()).isEqualTo(day);
        assertThat(point.trades()).isEqualTo(trades);
        assertThat(point.netPnl()).isEqualByComparingTo(net);
        assertThat(point.cumulativeNetPnl()).isEqualByComparingTo(cumulative);
    }

    private BigDecimal total(PerformanceFilter filter) {
        return statistics.counts(filter).totalNetPnl();
    }

    private PerformanceFilter all() {
        return filter(PerformanceMarket.ALL, PerformanceSource.ALL, null, null);
    }

    private PerformanceFilter filter(PerformanceMarket market, PerformanceSource source, UUID pair, UUID strategy) {
        return new PerformanceFilter(trader, T0, TO, market, source, pair, strategy);
    }

    private TradeRow closed(int day) {
        return TradeRow.closed(trader, btc, T0.plusSeconds(day * 24L * 3600));
    }

    private UUID plan(UUID pair) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, analysis_timeframe, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 100, 90, 110, 1000, 1, 'DRAFT', '1h', ?, ?)""").params(id, trader, pair, ts(T0), ts(T0)).update();
        return id;
    }

    private UUID pair(String prefix) {
        String symbol = prefix + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        return new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@performance.invalid", ts(T0), ts(T0)).update();
        accounts.add(id);
        return id;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
