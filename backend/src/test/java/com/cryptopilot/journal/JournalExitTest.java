package com.cryptopilot.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.cryptopilot.journal.service.FundingCorrectionService;
import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarkPrice;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.ClosedKlines;
import com.cryptopilot.support.PlanNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.job.MatchingWorker;
import com.cryptopilot.trading.job.MinuteKlineFeed;
import com.cryptopilot.trading.model.Excursion;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.MatchingService;
import com.cryptopilot.trading.service.TradingPlanService;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-048b acceptance: an open position exits through the matching engine and the real schema. The T-044 vectors (a) to
 * (c) are fed as 1-minute candles and mark prices to the running feed, and the journal row they close holds the exact
 * amounts, the Trader receives the exact MSG23 or MSG24, and the plan detail shows the closed position. A racing exit
 * and its replay close the row once; a close with a settlement missing stores the known sum and the flag, which the
 * late settlement clears; Spot stores no funding.
 *
 * <p>No network: the exchange is a stub of {@link MarketApi}: the pair's tick 0.01, one leverage bracket (MMR 0.004,
 * cum 0, the TD 7.6 vector) and the funding history the test sets. The fee rates are the seeded ones (Futures maker
 * 0.02 %, taker 0.05 %, clearance 1.25 %; Spot 0.1 %). Each test commits its writes; {@link #removeRows()} deletes them.
 *
 * <p>Rule: BR-34, BR-35, BR-36, BR-37, BR-38, NSF-07; D-97 and its note of 2026-10-24; Q-56, Q-57, Q-59, Q-61.
 */
@SpringBootTest(properties = {"cryptopilot.trading.matching.partitions=1"})
@Import(TestcontainersConfig.class)
class JournalExitTest {

    /** The administrator V3 seeded; any account will do as the plan's owner. */
    private static final UUID SEEDED_ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");
    private static final String SYMBOL = "JXUSDT";
    private static final FundingHistory NO_FUNDING =
            new FundingHistory(List.of(), List.of(), List.of(), List.of(), List.of());

    @MockitoBean
    private MarketApi market;

    @Autowired
    private MatchingWorker worker;

    @Autowired
    private MinuteKlineFeed feed;

    @Autowired
    private MatchingService matching;

    @Autowired
    private TradingPlanService tradingPlans;

    @Autowired
    private TradingPlanRepository plans;

    @Autowired
    private TradeJournal journal;

    @Autowired
    private FundingCorrectionService corrections;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final AtomicReference<FundingHistory> funding = new AtomicReference<>(NO_FUNDING);

    private UUID pair;
    private UUID baseCoin;
    private UUID quoteCoin;

    @BeforeEach
    void setUp() {
        feed.stop();
        worker.stop();
        insertPair();
        PairFilters filters = new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));
        when(market.tradablePair(eq(pair), any()))
                .thenAnswer(call -> Optional.of(new TradablePair(pair, SYMBOL, call.getArgument(1), filters)));
        when(market.leverageBrackets(pair))
                .thenReturn(List.of(new LeverageTier(
                        1, BigDecimal.ZERO, new BigDecimal("50000"), 125, new BigDecimal("0.004"), BigDecimal.ZERO)));
        when(market.fundingHistory(eq(pair), any(), any())).thenAnswer(call -> funding.get());
        when(market.closedMinuteKlines(any(), any(), any())).thenReturn(MinuteKlineBatch.of(List.of()));
        when(market.closedMarkPriceKlines(any(), any())).thenReturn(MinuteKlineBatch.of(List.of()));
        feed.start();
    }

    @AfterEach
    void removeRows() {
        feed.stop();
        worker.stop();
        PlanNotifications.removeOfPair(jdbc, pair);
        jdbc.sql("delete from trading_journal where pair_id = ?").param(pair).update();
        jdbc.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        jdbc.sql("delete from matching_watermark where pair_id = ?").param(pair).update();
        jdbc.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        jdbc.sql("delete from coin where coin_id in (?, ?)")
                .params(baseCoin, quoteCoin)
                .update();
    }

    /**
     * T-044 (a): a Futures LONG LIMIT 2 at 100 rests (maker 0.04) and its take profit 115 is reached: realized 30, fee
     * 0.04 + 0.046 = 0.086, no funding settled, net 29.914; MSG23 with that net. The exit candle opened below the
     * level, so the take profit fills at 115 (maker, never slipped).
     */
    @Test
    void BR35_caseA_aLongTakeProfit_closesTheJournalRow_andSendsMsg23() {
        UUID id = activePlan(PlanFixture.futuresLong().takeProfit("115"), EntryType.LIMIT);

        feed.onMinuteKline(closed(1, "100.5", "99.5", "101"));
        feed.onMinuteKline(closed(2, "110", "109", "116"));

        Map<String, Object> row = awaitClosed(id);
        assertThat(row.get("close_reason")).isEqualTo("TAKE_PROFIT");
        assertDecimal(row, "entry_price", "100");
        assertDecimal(row, "exit_price", "115");
        assertThat(((Timestamp) row.get("exit_time")).toInstant()).isEqualTo(minute(2));
        assertDecimal(row, "realized_pnl", "30");
        assertDecimal(row, "fee_amount", "0.086");
        assertDecimal(row, "funding_fee_amount", "0");
        assertThat(row.get("funding_incomplete")).isEqualTo(false);
        assertThat(row.get("missing_funding_times")).isNull();
        assertThat(awaitNotification(id)).isEqualTo(SYMBOL + " position closed by take profit at 115. P/L: 29.914.");
        assertThat(tradingPlans.get(SEEDED_ACCOUNT, id).position()).satisfies(position -> {
            assertThat(position.netPnl()).isEqualByComparingTo("29.914");
            assertThat(position.provisional()).isFalse();
            assertThat(position.exitTime()).isEqualTo(minute(2));
        });
        assertThat(journal.openPositionPlans(SEEDED_ACCOUNT)).doesNotContain(id);
    }

    /**
     * T-044 (b): a Futures SHORT 2 at 100 entered as taker (a STOP, fee 0.1) and stopped at 105: realized −10, fee 0.1
     * + 0.105 = 0.205, net −10.205; MSG23 with that net.
     */
    @Test
    void BR35_caseB_aShortStopLoss_closesTheJournalRow_andSendsMsg23() {
        UUID id = activePlan(PlanFixture.futuresShort().entry("100").stop("105").takeProfit("90"), EntryType.STOP);

        feed.onMinuteKline(closed(1, "101", "99.5", "101.5"));
        feed.onMinuteKline(closed(2, "103", "102", "106"));

        Map<String, Object> row = awaitClosed(id);
        assertThat(row.get("close_reason")).isEqualTo("STOP_LOSS");
        assertThat(row.get("entry_liquidity")).isEqualTo("TAKER");
        assertDecimal(row, "exit_price", "105");
        assertDecimal(row, "realized_pnl", "-10");
        assertDecimal(row, "fee_amount", "0.205");
        assertThat(awaitNotification(id)).isEqualTo(SYMBOL + " position closed by stop loss at 105. P/L: -10.205.");
    }

    /**
     * T-044 (c), TD 7.8: a Futures LONG 2 at 100, 10x, liquidated when the mark price falls to 90.37 (the TD 7.6 price
     * at its fill) while the last price stays above its stop loss: realized −19.26, clearance fee 0.72296, fee 0.76296,
     * net −20.02296; MSG24 "Loss: −20.02296".
     */
    @Test
    void BR35_caseC_aLiquidationByTheMarkPrice_closesTheJournalRow_andSendsMsg24() {
        UUID id = activePlan(PlanFixture.futuresLong().leverage(10).profile("1", 10), EntryType.LIMIT);

        feed.onMinuteKline(closed(1, "100.5", "99.5", "101"));
        awaitOpen(id);
        assertDecimal(row(id), "liquidation_price", "90.37");
        feed.onMarkPrice(new MarkPrice(pair, new BigDecimal("90.20"), minute(2).plusSeconds(10)));

        Map<String, Object> row = awaitClosed(id);
        assertThat(row.get("close_reason")).isEqualTo("LIQUIDATION");
        assertDecimal(row, "exit_price", "90.37");
        assertThat(((Timestamp) row.get("exit_time")).toInstant()).isEqualTo(minute(2));
        assertDecimal(row, "realized_pnl", "-19.26");
        assertDecimal(row, "fee_amount", "0.76296");
        assertThat(awaitNotification(id)).isEqualTo(SYMBOL + " position was liquidated at 90.37. Loss: -20.02296.");
    }

    /**
     * NSF-07: a live exit and a replay of the same candle race for one position; the compare-and-set on the journal row
     * lets exactly one through, and one notification is sent.
     */
    @RepeatedTest(3)
    void BR35_aRacingExitAndItsReplay_closeThePositionOnce() throws Exception {
        UUID id = activePlan(PlanFixture.futuresLong().takeProfit("115"), EntryType.LIMIT);
        feed.onMinuteKline(closed(1, "100.5", "99.5", "101"));
        awaitOpen(id);
        feed.stop();
        worker.stop();
        TrackedPosition position = matching.openPositions().stream()
                .filter(open -> open.planId().equals(id))
                .findFirst()
                .orElseThrow();
        PositionExit exit = new PositionExit(
                position,
                new Exit(id, CloseReason.TAKE_PROFIT, new BigDecimal("115"), minute(2)),
                Excursion.at(position.entryPrice()));
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<Boolean> close = () -> {
            together.await();
            return matching.close(exit);
        };

        List<Boolean> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> live = pool.submit(close);
            Future<Boolean> replay = pool.submit(close);
            outcomes.add(live.get());
            outcomes.add(replay.get());
        }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(matching.close(exit)).as("a later replay changes nothing").isFalse();
        assertDecimal(awaitClosed(id), "realized_pnl", "30");
        awaitNotification(id);
        assertThat(jdbc.sql(
                                "select count(*) from notification where reference_id = ? and source_key like 'POSITION_CLOSED:%'")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    /**
     * D-97 note of 2026-10-24: the 16:00 settlement is announced but not stored when the position closes at 20:00; the
     * close stores the known sum of the 08:00 settlement, the flag and 16:00, and the plan detail shows the net as
     * provisional. Once the late row is stored, the journal's correction stores the full sum and clears the flag.
     * LONG 2, marks 101 and 102, rate 0.0001: −0.0202, then −0.0202 − 0.0204 = −0.0406.
     */
    @Test
    void BR37_aCloseWithASettlementMissing_storesTheKnownSumAndTheFlag_andTheLateRowClearsIt() {
        Instant eight = Instant.parse("2026-10-25T08:00:00Z");
        Instant sixteen = Instant.parse("2026-10-25T16:00:00Z");
        SettledFunding first = new SettledFunding(eight, new BigDecimal("0.0001"), new BigDecimal("101"));
        SettledFunding late = new SettledFunding(sixteen, new BigDecimal("0.0001"), new BigDecimal("102"));
        funding.set(new FundingHistory(List.of(first), List.of(eight, sixteen), List.of(), List.of(), List.of()));
        UUID id = activePlan(PlanFixture.futuresLong().takeProfit("115"), EntryType.LIMIT, NOW.minusSeconds(3600));
        feed.onMinuteKline(closed(-60, "100.5", "99.5", "101"));
        awaitOpen(id);

        feed.onMinuteKline(closedAt(Instant.parse("2026-10-25T20:00:00Z"), "110", "109", "116"));

        Map<String, Object> row = awaitClosed(id);
        assertDecimal(row, "funding_fee_amount", "-0.0202");
        assertThat(row.get("funding_incomplete")).isEqualTo(true);
        assertThat(missingTimes(row)).containsExactly(sixteen);
        assertThat(tradingPlans.get(SEEDED_ACCOUNT, id).position()).satisfies(position -> {
            assertThat(position.fundingFeeAmount()).isEqualByComparingTo("-0.0202");
            assertThat(position.netPnl()).isEqualByComparingTo("29.8938");
            assertThat(position.provisional()).isTrue();
        });

        assertThat(corrections.correctIncompleteFunding(100)).isZero();
        funding.set(new FundingHistory(List.of(first, late), List.of(eight, sixteen), List.of(), List.of(), List.of()));
        assertThat(corrections.correctIncompleteFunding(100)).isEqualTo(1);

        Map<String, Object> corrected = row(id);
        assertDecimal(corrected, "funding_fee_amount", "-0.0406");
        assertThat(corrected.get("funding_incomplete")).isEqualTo(false);
        assertThat(corrected.get("missing_funding_times")).isNull();
        assertDecimal(corrected, "realized_pnl", "30");
        assertDecimal(corrected, "fee_amount", "0.086");
        assertThat(tradingPlans.get(SEEDED_ACCOUNT, id).position()).satisfies(position -> {
            assertThat(position.netPnl()).isEqualByComparingTo("29.8734");
            assertThat(position.provisional()).isFalse();
        });
    }

    /** BR-37: a Spot position has no funding: null amount, no flag, and its net is final. */
    @Test
    void BR37_aSpotClose_storesNoFunding_andNoFlag() {
        UUID id = activePlan(PlanFixture.spot().takeProfit("115"), EntryType.LIMIT);
        feed.onMinuteKline(closed(MarketType.SPOT, 1, "100.5", "99.5", "101"));
        feed.onMinuteKline(closed(MarketType.SPOT, 2, "110", "109", "116"));

        Map<String, Object> row = awaitClosed(id);
        assertThat(row.get("funding_fee_amount")).isNull();
        assertThat(row.get("funding_incomplete")).isEqualTo(false);
        assertThat(row.get("liquidation_price")).isNull();
        assertDecimal(row, "realized_pnl", "30");
        // Spot 0.1 % both ways: 100 × 2 × 0.001 + 115 × 2 × 0.001.
        assertDecimal(row, "fee_amount", "0.43");
        assertThat(tradingPlans.get(SEEDED_ACCOUNT, id).position().provisional())
                .isFalse();
    }

    /**
     * BR-27, D-123 (3): a STOP LONG 2 at 100, 10x, stop loss 95, planned with its liquidation price at 90.36, triggers
     * through a gap at the open 106. Recomputed at that fill, (212 − 21.2) / (2 × 0.996) = 95.783… → 95.79, past the
     * stop loss: the mark price falling to 95.5 liquidates the position at 95.79 while the last price never reaches the
     * stop. Realized (95.79 − 106) × 2 = −20.42.
     */
    @Test
    void BR27_aGapFillMovesTheLiquidationPricePastTheStop_andTheMarkLiquidatesBeforeTheStopLoss() {
        UUID id = activePlan(
                PlanFixture.futuresLong().takeProfit("130").leverage(10).profile("1", 10), EntryType.STOP);

        feed.onMinuteKline(closed(1, "106", "105", "107"));
        awaitOpen(id);
        Map<String, Object> open = row(id);
        assertDecimal(open, "entry_price", "106");
        assertDecimal(open, "liquidation_price", "95.79");
        assertThat((BigDecimal) open.get("liquidation_price")).isGreaterThan((BigDecimal) open.get("stop_loss_price"));
        feed.onMarkPrice(new MarkPrice(pair, new BigDecimal("95.5"), minute(2).plusSeconds(5)));

        Map<String, Object> row = awaitClosed(id);
        assertThat(row.get("close_reason")).isEqualTo("LIQUIDATION");
        assertDecimal(row, "exit_price", "95.79");
        assertDecimal(row, "realized_pnl", "-20.42");
    }

    /** Q-61: an exit candle that opened past the take profit and later traded through the stop closes at the TP. */
    @Test
    void Q61_anOpenPastTheTakeProfit_decides_evenThroughTheStopLater() {
        UUID id = activePlan(PlanFixture.futuresLong().takeProfit("115"), EntryType.LIMIT);
        feed.onMinuteKline(closed(1, "100.5", "99.5", "101"));
        feed.onMinuteKline(closed(2, "118", "94", "120"));

        Map<String, Object> row = awaitClosed(id);
        assertThat(row.get("close_reason")).isEqualTo("TAKE_PROFIT");
        assertDecimal(row, "exit_price", "115");
    }

    private UUID activePlan(PlanFixture fixture, EntryType entryType) {
        return activePlan(fixture, entryType, NOW);
    }

    private UUID activePlan(PlanFixture fixture, EntryType entryType, Instant activatedAt) {
        UUID id = inTransaction(() -> {
                    TradingPlan plan = TradingPlan.draft(
                            SEEDED_ACCOUNT,
                            pair,
                            fixture.entryType(entryType).build(),
                            new PlanDetails(
                                    entryType,
                                    activatedAt.plus(Duration.ofDays(7)),
                                    null,
                                    null,
                                    null,
                                    AnalysisTimeframe.ONE_HOUR),
                            List.of());
                    plan.activate(fixture.build(), List.of(), activatedAt);
                    return plans.save(plan);
                })
                .getId();
        // Start again so the waiting plan is in the books, as at a restart.
        feed.stop();
        worker.stop();
        feed.start();
        return id;
    }

    private Map<String, Object> awaitClosed(UUID planId) {
        awaitUntil(() -> "CLOSED".equals(status(planId)), "the journal row of " + planId + " closed");
        return row(planId);
    }

    private void awaitOpen(UUID planId) {
        awaitUntil(() -> "OPEN".equals(status(planId)), "the journal row of " + planId + " open");
    }

    private String awaitNotification(UUID planId) {
        awaitUntil(
                () -> jdbc.sql(
                                        "select count(*) from notification where reference_id = ? and source_key like 'POSITION_CLOSED:%'")
                                .param(planId)
                                .query(Long.class)
                                .single()
                        > 0,
                "the notification of " + planId);
        return jdbc.sql(
                        "select content from notification where reference_id = ? and source_key like 'POSITION_CLOSED:%'")
                .param(planId)
                .query(String.class)
                .single();
    }

    private String status(UUID planId) {
        return jdbc.sql("select trade_status from trading_journal where plan_id = ?")
                .param(planId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private Map<String, Object> row(UUID planId) {
        return jdbc.sql("select * from trading_journal where plan_id = ?")
                .param(planId)
                .query()
                .singleRow();
    }

    private static List<Instant> missingTimes(Map<String, Object> row) {
        try {
            Object[] times = (Object[]) ((Array) row.get("missing_funding_times")).getArray();
            List<Instant> instants = new ArrayList<>();
            for (Object time : times) {
                instants.add(((Timestamp) time).toInstant());
            }
            return instants;
        } catch (SQLException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static void assertDecimal(Map<String, Object> row, String column, String expected) {
        assertThat((BigDecimal) row.get(column)).as(column).isEqualByComparingTo(expected);
    }

    private MinuteKline closed(int minute, String open, String low, String high) {
        return closed(MarketType.FUTURES, minute, open, low, high);
    }

    private MinuteKline closed(MarketType marketType, int minute, String open, String low, String high) {
        return closedAt(marketType, minute(minute), open, low, high);
    }

    private MinuteKline closedAt(Instant openTime, String open, String low, String high) {
        return closedAt(MarketType.FUTURES, openTime, open, low, high);
    }

    private MinuteKline closedAt(MarketType marketType, Instant openTime, String open, String low, String high) {
        return ClosedKlines.closed(marketType, pair, openTime, open, low, high);
    }

    private static Instant minute(long n) {
        return NOW.plus(Duration.ofMinutes(n));
    }

    private void insertPair() {
        OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        baseCoin = UUID.randomUUID();
        quoteCoin = UUID.randomUUID();
        pair = UUID.randomUUID();
        for (Object[] coin : new Object[][] {{baseCoin, "JXB" + suffix}, {quoteCoin, "JXQ" + suffix}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], at, at)
                    .update();
        }
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 spot_exchange_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'INACTIVE', 'TRADING', ?, ?)""").params(pair, baseCoin, quoteCoin, "JX" + suffix, at, at).update();
    }

    private static void awaitUntil(Supplier<Boolean> condition, String what) {
        await(what)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(condition::get);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }
}
