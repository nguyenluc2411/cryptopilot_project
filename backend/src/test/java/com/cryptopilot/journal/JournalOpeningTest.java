package com.cryptopilot.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.SimulatedTradeOpening;
import com.cryptopilot.trading.TradeJournal;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.PositionStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.MatchingService;
import com.cryptopilot.trading.service.TradingPlanService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The OPEN journal record of a fill against the real schema: written by the transaction that moved the plan to
 * EXECUTED, never by one that found it no longer ACTIVE, at most once per plan, and rolled back with the fill. Each
 * test commits its own writes; {@link #removeRows()} deletes them.
 *
 * <p>Rule: BR-34, BR-36, NSF-07; D-78, D-119.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class JournalOpeningTest {

    /** The administrator V3 seeded; any account will do as the plan's owner. */
    private static final UUID SEEDED_ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");
    private static final Instant FILLED_AT = NOW.plusSeconds(60);
    private static final PlanDetails LIMIT = new PlanDetails(
            EntryType.LIMIT, NOW.plus(Duration.ofDays(7)), null, null, null, AnalysisTimeframe.ONE_HOUR);

    /** The Futures maker rate V3 seeded. */
    private static final BigDecimal FUTURES_MAKER = new BigDecimal("0.0002");

    @Autowired
    private MatchingService matching;

    @Autowired
    private TradingPlanService tradingPlans;

    @Autowired
    private TradingPlanRepository plans;

    @Autowired
    private TradeJournal journal;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private UUID pair;
    private UUID baseCoin;
    private UUID quoteCoin;

    @BeforeEach
    void insertPair() {
        OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        baseCoin = UUID.randomUUID();
        quoteCoin = UUID.randomUUID();
        pair = UUID.randomUUID();
        for (Object[] coin : new Object[][] {{baseCoin, "JOB" + suffix}, {quoteCoin, "JOQ" + suffix}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], at, at)
                    .update();
        }
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 spot_exchange_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'INACTIVE', 'TRADING', ?, ?)""").params(pair, baseCoin, quoteCoin, "JO" + suffix, at, at).update();
    }

    @AfterEach
    void removeRows() {
        jdbc.sql("delete from trading_journal where pair_id = ?").param(pair).update();
        jdbc.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        jdbc.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        jdbc.sql("delete from coin where coin_id in (?, ?)")
                .params(baseCoin, quoteCoin)
                .update();
    }

    /**
     * BR-34: the engine's fill of a resting LIMIT opens a SIMULATED record with the fill's price and candle time (D-78),
     * the snapshot's quantity, MAKER and the entry fee at the seeded Futures maker rate, and the plan's levels.
     */
    @Test
    void BR34_aRestingLimitFilledByTheEngine_opensItsRecord() {
        UUID id = activePlan();
        TradingPlan plan = plan(id);
        BigDecimal quantity = plan.getSnapshot().positionQuantity();

        assertThat(matching.fill(limitFill(id, new BigDecimal("99.5")))).isTrue();

        Map<String, Object> row = record(id);
        assertThat(row.get("source")).isEqualTo("SIMULATED");
        assertThat(row.get("trade_status")).isEqualTo("OPEN");
        assertThat(row.get("user_id")).isEqualTo(SEEDED_ACCOUNT);
        assertThat(row.get("pair_id")).isEqualTo(pair);
        assertThat(row.get("market_type")).isEqualTo(MarketType.FUTURES.name());
        assertThat(row.get("direction")).isEqualTo(Direction.LONG.name());
        assertThat(row.get("leverage")).isEqualTo(plan.getLeverage());
        assertThat((BigDecimal) row.get("entry_price")).isEqualByComparingTo("99.5");
        assertThat(((Timestamp) row.get("entry_time")).toInstant()).isEqualTo(FILLED_AT);
        assertThat((BigDecimal) row.get("quantity")).isEqualByComparingTo(quantity);
        assertThat(row.get("entry_liquidity")).isEqualTo(LiquidityRole.MAKER.name());
        assertThat((BigDecimal) row.get("entry_fee_amount"))
                .isEqualByComparingTo(new BigDecimal("99.5")
                        .multiply(quantity)
                        .multiply(FUTURES_MAKER)
                        .setScale(8, RoundingMode.HALF_EVEN));
        assertThat((BigDecimal) row.get("stop_loss_price")).isEqualByComparingTo(plan.getStopLoss());
        assertThat((BigDecimal) row.get("take_profit_price")).isEqualByComparingTo(plan.getTakeProfit());
        assertThat(row.get("fee_amount"))
                .as("entry + exit, written at the close (T-048b)")
                .isNull();
        assertThat(row.get("exit_price")).isNull();
        assertThat(journal.openPositionPlans(SEEDED_ACCOUNT)).contains(id);
        assertThat(journal.positionOf(id)).hasValueSatisfying(position -> {
            assertThat(position.status()).isEqualTo(PositionStatus.OPEN);
            assertThat(position.entryTime()).isEqualTo(FILLED_AT);
        });
    }

    /** BR-34: a fill that finds the plan cancelled first changes nothing and writes no record. */
    @Test
    void BR34_aFillThatFindsThePlanNoLongerActive_writesNoRecord() {
        UUID id = activePlan();
        tradingPlans.cancel(SEEDED_ACCOUNT, id);

        assertThat(matching.fill(limitFill(id, new BigDecimal("100")))).isFalse();

        assertThat(records(id)).isZero();
    }

    /**
     * BR-34, NSF-07: the live fill and a replay of the same candle race for one plan; the compare-and-set lets exactly
     * one through, and exactly one record exists.
     */
    @RepeatedTest(5)
    void BR34_aFillRacingAReplayOfTheSamePlan_opensExactlyOneRecord() throws Exception {
        UUID id = activePlan();
        Fill same = limitFill(id, new BigDecimal("100"));
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<Boolean> fill = () -> {
            together.await();
            return matching.fill(same);
        };

        List<Boolean> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> live = pool.submit(fill);
            Future<Boolean> replay = pool.submit(fill);
            outcomes.add(live.get());
            outcomes.add(replay.get());
        }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(records(id)).isEqualTo(1);
        assertThat(plan(id).getStatus()).isEqualTo(PlanStatus.EXECUTED);
    }

    /** BR-34: {@code UNIQUE (plan_id)} refuses a second record of a plan, inside the call that writes it. */
    @Test
    void BR34_aSecondRecordOfAPlan_isRefusedByTheDatabase() {
        UUID id = activePlan();
        assertThat(matching.fill(limitFill(id, new BigDecimal("100")))).isTrue();

        assertThatThrownBy(() -> inTransaction(() -> {
                    journal.openSimulatedTrade(opening(id));
                    return null;
                }))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(records(id)).isEqualTo(1);
    }

    /**
     * BR-34: the record and the fill commit together. With a record already in place the journal's insert fails, and
     * the fill's compare-and-set rolls back with it: the plan is still ACTIVE.
     */
    @Test
    void BR34_aRecordThatCannotBeWritten_rollsTheFillBack() {
        UUID id = activePlan();
        inTransaction(() -> {
            journal.openSimulatedTrade(opening(id));
            return null;
        });

        assertThatThrownBy(() -> matching.fill(limitFill(id, new BigDecimal("100"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(plan(id).getStatus()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan(id).getExecutedAt()).isNull();
    }

    /** The engine's fill of the plan's resting LIMIT at {@code price}: MAKER, at the candle time. */
    private Fill limitFill(UUID id, BigDecimal price) {
        TradingPlan plan = plan(id);
        TrackedEntry entry = new TrackedEntry(
                id,
                plan.getUserId(),
                plan.getMarket(),
                plan.getPairId(),
                plan.getDirection(),
                plan.getEntryType(),
                plan.getEntryPrice(),
                plan.getStopLoss(),
                plan.getTakeProfit(),
                plan.getInvalidationPrice(),
                plan.getActivatedAt());
        return new Fill(entry, LiquidityRole.MAKER, price, FILLED_AT);
    }

    /** D-119: the journal never opens a record in a transaction of its own. */
    @Test
    void D119_aRecordOutsideAFillsTransaction_isRefused() {
        UUID id = activePlan();

        assertThatThrownBy(() -> journal.openSimulatedTrade(opening(id)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(records(id)).isZero();
    }

    private SimulatedTradeOpening opening(UUID planId) {
        return new SimulatedTradeOpening(
                planId,
                SEEDED_ACCOUNT,
                pair,
                MarketType.FUTURES,
                Direction.LONG,
                5,
                new BigDecimal("100"),
                FILLED_AT,
                BigDecimal.ONE,
                LiquidityRole.MAKER,
                new BigDecimal("0.02"),
                new BigDecimal("95"),
                new BigDecimal("110"));
    }

    private UUID activePlan() {
        return inTransaction(() -> {
                    TradingPlan plan = TradingPlan.draft(
                            SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), LIMIT, List.of());
                    plan.activate(PlanFixture.futuresLong().build(), List.of(), NOW);
                    return plans.save(plan);
                })
                .getId();
    }

    private Map<String, Object> record(UUID planId) {
        return jdbc.sql("select * from trading_journal where plan_id = ?")
                .param(planId)
                .query()
                .singleRow();
    }

    private long records(UUID planId) {
        return jdbc.sql("select count(*) from trading_journal where plan_id = ?")
                .param(planId)
                .query(Long.class)
                .single();
    }

    private TradingPlan plan(UUID id) {
        return inTransaction(() -> plans.findById(id).orElseThrow());
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }
}
