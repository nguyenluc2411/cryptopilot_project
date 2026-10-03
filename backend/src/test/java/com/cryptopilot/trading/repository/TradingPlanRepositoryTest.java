package com.cryptopilot.trading.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.PlanCalculation;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PlanSnapshot;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.MarginMode;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.model.enums.WarningType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The plan aggregate against the schema Flyway migrated: every column written and read back, the warnings saved and
 * replaced through the plan, the status instants of V15 and the optimistic lock.
 *
 * <p>Not one rolled-back transaction: the optimistic lock can only be shown with two committed writes, so each test
 * writes in its own transactions and {@link #removeRows()} deletes what it wrote. The plans belong to the account V3
 * seeded and to a pair inserted here.
 *
 * <p>Rule: BR-31, BR-32; TECHNICAL_DESIGN 5.5; D-67, D-68.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfig.class)
class TradingPlanRepositoryTest {

    /** The administrator V3 seeded; any account will do as the plan's owner. */
    private static final UUID SEEDED_ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-10-08T08:00:00Z");
    private static final PlanDetails LIMIT = new PlanDetails(EntryType.LIMIT, EXPIRY, "retest of 100");

    private static final PlanWarning BLOCKING =
            new PlanWarning(WarningType.INSUFFICIENT_CAPITAL, "margin above capital");
    private static final PlanWarning WARNING = new PlanWarning(WarningType.LOW_RR, "risk/reward 1.2");
    private static final PlanWarning INFO = new PlanWarning(WarningType.WIDE_STOP_LOSS, "stop 12 % away");

    @Autowired
    private TradingPlanRepository plans;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory emf;

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
        for (Object[] coin : new Object[][] {{baseCoin, "TPB" + suffix}, {quoteCoin, "TPQ" + suffix}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], at, at)
                    .update();
        }
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 spot_exchange_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'INACTIVE', 'TRADING', ?, ?)""").params(pair, baseCoin, quoteCoin, "TP" + suffix, at, at).update();
    }

    @AfterEach
    void removeRows() {
        jdbc.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        jdbc.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        jdbc.sql("delete from coin where coin_id in (?, ?)")
                .params(baseCoin, quoteCoin)
                .update();
    }

    @Test
    void BR31_aFuturesPlan_survivesAWriteAndAReadWithEveryColumnAndItsWarnings() {
        PlanCalculation calculation = PlanFixture.futuresLong().build();
        UUID id = inTransaction(() -> plans.save(
                        TradingPlan.draft(SEEDED_ACCOUNT, pair, calculation, LIMIT, List.of(BLOCKING, WARNING, INFO))))
                .getId();

        inTransaction(() -> {
            TradingPlan reread = plans.findById(id).orElseThrow();
            assertThat(reread.getUserId()).isEqualTo(SEEDED_ACCOUNT);
            assertThat(reread.getPairId()).isEqualTo(pair);
            assertThat(reread.getStatus()).isEqualTo(PlanStatus.DRAFT);
            assertThat(reread.getEntryType()).isEqualTo(EntryType.LIMIT);
            assertThat(reread.getEntryPrice()).isEqualByComparingTo("100");
            assertThat(reread.getStopLoss()).isEqualByComparingTo("95");
            assertThat(reread.getTakeProfit()).isEqualByComparingTo("110");
            assertThat(reread.getCapital()).isEqualByComparingTo("1000");
            assertThat(reread.getRiskPercent()).isEqualByComparingTo("1");
            assertThat(reread.getLeverage()).isEqualTo(5);
            assertThat(reread.getMarginMode()).isEqualTo(MarginMode.ISOLATED);
            assertThat(reread.getExpiresAt()).isEqualTo(EXPIRY);
            assertThat(reread.getNote()).isEqualTo("retest of 100");
            assertThat(reread.getSnapshot())
                    .as("stored at the scale the snapshot already has")
                    .isEqualTo(PlanSnapshot.of(calculation));
            assertThat(reread.getWarnings())
                    .as("in the order they were raised")
                    .containsExactly(BLOCKING, WARNING, INFO);
            assertThat(reread.getCreatedAt()).isNotNull();
            assertThat(reread.getVersion()).isZero();
            return null;
        });
    }

    @Test
    void BR21_aSpotPlan_storesNullInEveryFuturesColumn() {
        UUID id = inTransaction(() -> plans.save(TradingPlan.draft(
                        SEEDED_ACCOUNT, pair, PlanFixture.spot().build(), LIMIT, List.of())))
                .getId();

        assertThat(jdbc.sql("""
                        select count(*) from trading_plan
                        where plan_id = ? and leverage is null and margin_mode is null and initial_margin is null
                          and maintenance_margin_rate_used is null and estimated_liquidation_price is null""").param(id).query(Long.class).single()).isEqualTo(1L);
    }

    /** V16: a far take profit over a near stop is a valid plan, and its ratio of 10,000 or more is stored. */
    @Test
    void BR24_aRiskRewardRatioOfTenThousand_isStored() {
        PlanCalculation calculation = PlanFixture.spot()
                .stop("99.99")
                .takeProfit("200")
                .capital("1000000")
                .build();
        UUID id = inTransaction(
                        () -> plans.save(TradingPlan.draft(SEEDED_ACCOUNT, pair, calculation, LIMIT, List.of())))
                .getId();

        assertThat(jdbc.sql("select risk_reward_ratio from trading_plan where plan_id = ?")
                        .param(id)
                        .query(java.math.BigDecimal.class)
                        .single())
                .isEqualByComparingTo("10000");
    }

    @Test
    void BR31_savingADraftAgain_deletesTheWarningsItReplaces() {
        UUID id = inTransaction(() -> plans.save(TradingPlan.draft(
                        SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), LIMIT, List.of(BLOCKING, WARNING))))
                .getId();

        inTransaction(() -> {
            plans.findById(id)
                    .orElseThrow()
                    .updateDraft(PlanFixture.futuresLong().build(), LIMIT, List.of(INFO));
            return null;
        });

        assertThat(jdbc.sql("select warning_type from trading_plan_warning where plan_id = ?")
                        .param(id)
                        .query(String.class)
                        .list())
                .containsExactly("WIDE_STOP_LOSS");
    }

    @Test
    void BR32_eachStatusInstant_isStoredWithItsStatus() {
        UUID id = inTransaction(() -> plans.save(TradingPlan.draft(
                        SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), LIMIT, List.of())))
                .getId();

        inTransaction(() -> {
            TradingPlan plan = plans.findById(id).orElseThrow();
            plan.activate(PlanFixture.futuresLong().build(), List.of(WARNING), NOW);
            plan.markExecuted(NOW.plusSeconds(60), new BigDecimal("101.25"));
            return null;
        });

        inTransaction(() -> {
            TradingPlan reread = plans.findById(id).orElseThrow();
            assertThat(reread.getStatus()).isEqualTo(PlanStatus.EXECUTED);
            assertThat(reread.getActivatedAt()).isEqualTo(NOW);
            assertThat(reread.getExecutedAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(reread.getFillPrice()).isEqualByComparingTo("101.25");
            assertThat(reread.getCancelledAt()).isNull();
            assertThat(reread.getExpiredAt()).isNull();
            return null;
        });
    }

    /** V15: a status without its instant, or an instant without its status, is refused by the database itself. */
    @Test
    void BR32_theSchema_refusesAStatusWithoutItsInstant() {
        UUID id = inTransaction(() -> plans.save(TradingPlan.draft(
                        SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), LIMIT, List.of())))
                .getId();
        OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);

        assertThatThrownBy(() -> jdbc.sql("update trading_plan set plan_status = 'CANCELLED' where plan_id = ?")
                        .param(id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trading_plan_cancelled_at");
        assertThatThrownBy(() -> jdbc.sql("update trading_plan set expired_at = ? where plan_id = ?")
                        .params(at, id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trading_plan_expired_at");
        assertThatThrownBy(() -> jdbc.sql(
                                "update trading_plan set plan_status = 'EXECUTED', executed_at = ? where plan_id = ?")
                        .params(at, id)
                        .update())
                .as("EXECUTED follows ACTIVE, so it needs an activation instant")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trading_plan_activated_at");
    }

    /** V18: an EXECUTED plan has its fill price, and only an EXECUTED plan has one. */
    @Test
    void BR33_theSchema_refusesAnExecutedPlanWithoutItsFillPrice_andAFillPriceWithoutTheStatus() {
        UUID id = inTransaction(() -> {
                    TradingPlan plan = TradingPlan.draft(
                            SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), LIMIT, List.of());
                    plan.activate(PlanFixture.futuresLong().build(), List.of(), NOW);
                    return plans.save(plan);
                })
                .getId();
        OffsetDateTime at = NOW.plusSeconds(60).atOffset(ZoneOffset.UTC);

        assertThatThrownBy(() -> jdbc.sql(
                                "update trading_plan set plan_status = 'EXECUTED', executed_at = ? where plan_id = ?")
                        .params(at, id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trading_plan_fill_price");
        assertThatThrownBy(() -> jdbc.sql("update trading_plan set fill_price = 100 where plan_id = ?")
                        .param(id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trading_plan_fill_price");
    }

    @Test
    void aStaleCopy_cannotOverwriteAConcurrentChange() {
        UUID id = inTransaction(() -> plans.save(TradingPlan.draft(
                        SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), LIMIT, List.of())))
                .getId();
        TransactionTemplate concurrent = new TransactionTemplate(transactions);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> inTransaction(() -> {
                    TradingPlan stale = plans.findById(id).orElseThrow();
                    concurrent.executeWithoutResult(
                            other -> plans.findById(id).orElseThrow().cancel(NOW));
                    stale.activate(PlanFixture.futuresLong().build(), List.of(), NOW);
                    return null;
                }))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.sql("select plan_status from trading_plan where plan_id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("CANCELLED");
    }

    /**
     * What the one association costs (D-68): a plan is read in one statement and its warnings only when asked for, in
     * one more; a list of plans is one statement however many there are.
     */
    @Test
    void readingPlans_costsOneStatement_andTheirWarningsOneMoreWhenRead() {
        UUID id = inTransaction(() -> {
                    for (int i = 0; i < 2; i++) {
                        plans.save(TradingPlan.draft(
                                SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), LIMIT, List.of(WARNING)));
                    }
                    return plans.save(TradingPlan.draft(
                            SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), LIMIT, List.of(BLOCKING, INFO)));
                })
                .getId();
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();

        inTransaction(() -> {
            statistics.clear();
            List<TradingPlan> listed = em.createQuery(
                            "select p from TradingPlan p where p.pairId = :pair", TradingPlan.class)
                    .setParameter("pair", pair)
                    .getResultList();
            listed.forEach(TradingPlan::getStatus);
            assertThat(listed).hasSize(3);
            assertThat(statistics.getPrepareStatementCount())
                    .as("three plans, one statement")
                    .isEqualTo(1L);
            return null;
        });

        inTransaction(() -> {
            statistics.clear();
            TradingPlan plan = plans.findById(id).orElseThrow();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
            assertThat(plan.getWarnings()).containsExactly(BLOCKING, INFO);
            assertThat(statistics.getPrepareStatementCount())
                    .as("the warnings, read when asked for")
                    .isEqualTo(2L);
            return null;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }
}
