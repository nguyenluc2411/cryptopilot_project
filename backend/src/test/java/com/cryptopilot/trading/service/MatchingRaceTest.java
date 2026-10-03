package com.cryptopilot.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.event.TradingPlanActivated;
import com.cryptopilot.trading.exception.IllegalPlanStateException;
import com.cryptopilot.trading.job.MatchingWorker;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PlanStatus;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The compare-and-set fill against the real schema: a fill and a cancel of the same ACTIVE plan race, and exactly one
 * of them changes it. Each test commits its own writes; {@link #removeRows()} deletes them.
 *
 * <p>Rule: NSF-07, BR-32, UC-19; TECHNICAL_DESIGN 5.5 and 7.7.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MatchingRaceTest {

    /** The administrator V3 seeded; any account will do as the plan's owner. */
    private static final UUID SEEDED_ACCOUNT = UUID.fromString("019b76da-a800-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
    private static final Instant FILLED_AT = NOW.plusSeconds(60);
    private static final PlanDetails LIMIT = new PlanDetails(EntryType.LIMIT, NOW.plus(Duration.ofDays(7)), null);
    private static final PlanDetails MARKET = new PlanDetails(EntryType.MARKET, null, null);

    @Autowired
    private MatchingService matching;

    @Autowired
    private TradingPlanService tradingPlans;

    @Autowired
    private TradingPlanRepository plans;

    @Autowired
    private MatchingWorker worker;

    @Autowired
    private ApplicationEventPublisher events;

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
        for (Object[] coin : new Object[][] {{baseCoin, "MRB" + suffix}, {quoteCoin, "MRQ" + suffix}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], at, at)
                    .update();
        }
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 spot_exchange_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'INACTIVE', 'TRADING', ?, ?)""").params(pair, baseCoin, quoteCoin, "MR" + suffix, at, at).update();
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
    void NSF07_anActivePlan_isFilledOnce_withItsFillInstantAndANewVersion() {
        UUID id = activePlan(LIMIT);
        long version = plan(id).getVersion();

        assertThat(matching.fill(id, FILLED_AT)).isTrue();
        assertThat(matching.fill(id, FILLED_AT.plusSeconds(60)))
                .as("a second fill finds nothing ACTIVE")
                .isFalse();

        TradingPlan filled = plan(id);
        assertThat(filled.getStatus()).isEqualTo(PlanStatus.EXECUTED);
        assertThat(filled.getExecutedAt()).isEqualTo(FILLED_AT);
        assertThat(filled.getVersion()).isEqualTo(version + 1);
    }

    @Test
    void NSF07_aDraftOrACancelledPlan_isNeverFilled() {
        UUID draft = inTransaction(() -> plans.save(draft(LIMIT))).getId();
        UUID cancelled = activePlan(LIMIT);
        tradingPlans.cancel(SEEDED_ACCOUNT, cancelled);

        assertThat(matching.fill(draft, FILLED_AT)).isFalse();
        assertThat(matching.fill(cancelled, FILLED_AT)).isFalse();
        assertThat(plan(draft).getStatus()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan(cancelled).getStatus()).isEqualTo(PlanStatus.CANCELLED);
    }

    @Test
    void NSF07_theBooksStart_fromTheActiveLimitPlansOnly() {
        UUID limit = activePlan(LIMIT);
        UUID market = activePlan(MARKET);
        UUID draft = inTransaction(() -> plans.save(draft(LIMIT))).getId();

        assertThat(matching.activeEntries())
                .extracting(TrackedEntry::planId)
                .contains(limit)
                .doesNotContain(market, draft);
        assertThat(matching.activeEntries())
                .filteredOn(entry -> entry.planId().equals(limit))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.pairId()).isEqualTo(pair);
                    assertThat(entry.entryPrice()).isEqualByComparingTo("100");
                });
    }

    @RepeatedTest(20)
    void NSF07_aCancelAndAFillOfTheSamePlan_haveExactlyOneWinner() throws Exception {
        UUID id = activePlan(LIMIT);
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<Boolean> fill = () -> {
            together.await();
            return matching.fill(id, FILLED_AT);
        };
        Callable<Boolean> cancel = () -> {
            together.await();
            try {
                tradingPlans.cancel(SEEDED_ACCOUNT, id);
                return true;
            } catch (OptimisticLockingFailureException | IllegalPlanStateException lost) {
                return false;
            }
        };

        boolean filled;
        boolean cancelled;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> filling = pool.submit(fill);
            Future<Boolean> cancelling = pool.submit(cancel);
            filled = filling.get();
            cancelled = cancelling.get();
        }

        assertThat(filled ^ cancelled)
                .as("filled=%s cancelled=%s", filled, cancelled)
                .isTrue();
        TradingPlan after = plan(id);
        if (filled) {
            assertThat(after.getStatus()).isEqualTo(PlanStatus.EXECUTED);
            assertThat(after.getCancelledAt()).isNull();
        } else {
            assertThat(after.getStatus()).isEqualTo(PlanStatus.CANCELLED);
            assertThat(after.getExecutedAt()).isNull();
        }
    }

    @Test
    void NSF07_aLimitPlanActivatedAfterStart_isFilledByARangeThatReachesIt() throws InterruptedException {
        UUID id = activePlan(LIMIT);
        inTransaction(() -> {
            events.publishEvent(activated(id));
            return null;
        });

        // The listener queued the entry on commit, so this range reaches its partition after it.
        assertThat(worker.submit(new PriceRange(
                        MarketType.FUTURES, pair, new BigDecimal("99"), new BigDecimal("101"), FILLED_AT)))
                .isTrue();

        Instant deadline = Instant.now().plusSeconds(10);
        while (plan(id).getStatus() != PlanStatus.EXECUTED && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        assertThat(plan(id).getExecutedAt()).isEqualTo(FILLED_AT);
    }

    private TradingPlanActivated activated(UUID id) {
        TradingPlan plan = plan(id);
        return new TradingPlanActivated(
                id, plan.getMarket(), pair, plan.getDirection(), plan.getEntryType(), plan.getEntryPrice());
    }

    private UUID activePlan(PlanDetails details) {
        return inTransaction(() -> {
                    TradingPlan plan = draft(details);
                    plan.activate(PlanFixture.futuresLong().build(), List.of(), NOW);
                    return plans.save(plan);
                })
                .getId();
    }

    private TradingPlan draft(PlanDetails details) {
        return TradingPlan.draft(SEEDED_ACCOUNT, pair, PlanFixture.futuresLong().build(), details, List.of());
    }

    private TradingPlan plan(UUID id) {
        return inTransaction(() -> plans.findById(id).orElseThrow());
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }
}
