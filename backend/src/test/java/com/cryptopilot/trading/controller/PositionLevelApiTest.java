package com.cryptopilot.trading.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
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
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TP-E acceptance, end to end on the real schema: a Trader changes the stop loss and take profit of an OPEN Futures
 * position through {@code PATCH /api/v1/plans/{id}/position-levels}; the journal row keeps the original levels, the
 * change count, the {@code stop_widened} flag and the history; the matching engine exits at the new levels (a moved stop
 * as a taker with slippage, a moved take profit as a maker at its price through a gap); a change racing an engine exit
 * has one outcome; and a replay after a crash ends the position as live processing does.
 *
 * <p>The position: a Futures LONG LIMIT 2 at 100, stop 95, take profit 110, leverage 5, filled by the candle of minute 1
 * as a maker (fee 0.04). The Trader has a default capital of 1,000 on BALANCED (1 % per trade, 4 % total).
 * {@code SLIPPAGE_BPS} is set to 10 through the admin endpoint and restored after each test. The clock is the test's
 * and moves with the candles; the exchange is a stub of {@link MarketApi}.
 *
 * <p>Rule: UC-21; BR-22, BR-28, BR-29, BR-35, BR-36, BR-42, NSF-07; D-63, D-77, D-79, D-119 (5), D-122, D-124.
 */
@SpringBootTest(
        properties = {
            "cryptopilot.trading.matching.partitions=1",
            "cryptopilot.trading.matching.replay.enabled=true",
            "cryptopilot.trading.matching.replay.catch-up-attempts=0",
            "cryptopilot.trading.matching.replay.catch-up-wait=0s"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, PositionLevelApiTest.MovableClockConfig.class})
class PositionLevelApiTest {

    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");
    private static final String SYMBOL = "PLUSDT";
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
    private TradingPlanRepository plans;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MovableClock clock;

    private final AtomicReference<BigDecimal> lastPrice = new AtomicReference<>(new BigDecimal("101"));

    /** The closed candles the exchange stub serves to a replay, three a page. */
    private final List<MinuteKline> served = new CopyOnWriteArrayList<>();

    /** Called once by the exchange stub, while a replay fetches its first page. */
    private volatile Runnable duringReplay;

    private final List<UUID> pairs = new ArrayList<>();
    private final List<UUID> coins = new ArrayList<>();
    private UUID pair;
    private UUID trader;
    private UUID admin;
    private Map<String, Object> settingAudit;

    @BeforeEach
    void setUp() throws Exception {
        feed.stop();
        worker.stop();
        clock.set(NOW);
        trader = account(UserRole.TRADER);
        jdbc.sql("""
                        insert into user_profile (user_id, display_name, default_capital, risk_profile, created_at,
                                                  updated_at)
                        values (?, 'Trader', 1000, 'BALANCED', ?, ?)""").params(trader, now(), now()).update();
        admin = account(UserRole.ADMIN);
        settingAudit = jdbc.sql("select updated_by, updated_at from system_setting where setting_key = 'SLIPPAGE_BPS'")
                .query()
                .singleRow();
        changeSlippage("10");
        when(market.tradablePair(any(), any()))
                .thenAnswer(call -> Optional.of(new TradablePair(
                        call.getArgument(0),
                        SYMBOL,
                        call.getArgument(1),
                        new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5")))));
        when(market.leverageBrackets(any()))
                .thenReturn(List.of(new LeverageTier(
                        1, BigDecimal.ZERO, new BigDecimal("50000"), 125, new BigDecimal("0.004"), BigDecimal.ZERO)));
        when(market.fundingHistory(any(), any(), any())).thenReturn(NO_FUNDING);
        when(market.currentLastPrice(eq(MarketType.FUTURES), eq(SYMBOL)))
                .thenAnswer(call -> Optional.ofNullable(lastPrice.get()));
        when(market.closedMinuteKlines(eq(MarketType.FUTURES), any(), any())).thenAnswer(call -> {
            UUID of = call.getArgument(1);
            Instant from = call.getArgument(2);
            Runnable hook = duringReplay;
            if (hook != null) {
                duringReplay = null;
                hook.run();
            }
            return MinuteKlineBatch.of(served.stream()
                    .filter(kline ->
                            kline.pairId().equals(of) && !kline.openTime().isBefore(from))
                    .limit(3)
                    .toList());
        });
        when(market.closedMarkPriceKlines(any(), any())).thenReturn(MinuteKlineBatch.of(List.of()));
    }

    @AfterEach
    void tearDown() throws Exception {
        feed.stop();
        worker.stop();
        try {
            changeSlippage("0");
        } finally {
            jdbc.sql("update system_setting set updated_by = ?, updated_at = ? where setting_key = 'SLIPPAGE_BPS'")
                    .params(settingAudit.get("updated_by"), settingAudit.get("updated_at"))
                    .update();
            jdbc.sql("delete from audit_log where user_id = ?").param(admin).update();
            for (UUID each : pairs) {
                deleteNotifications(each);
                jdbc.sql("""
                                delete from trading_journal_level_change where journal_id in
                                       (select journal_id from trading_journal where pair_id = ?)""").param(each).update();
                jdbc.sql("delete from trading_journal where pair_id = ?")
                        .param(each)
                        .update();
                jdbc.sql("delete from trading_plan where pair_id = ?")
                        .param(each)
                        .update();
                jdbc.sql("delete from matching_watermark where pair_id = ?")
                        .param(each)
                        .update();
                jdbc.sql("delete from crypto_pair where pair_id = ?")
                        .param(each)
                        .update();
            }
            for (UUID coin : coins) {
                jdbc.sql("delete from coin where coin_id = ?").param(coin).update();
            }
            jdbc.sql("delete from user_profile where user_id = ?").param(trader).update();
            jdbc.sql("delete from user_account where user_id in (?, ?)")
                    .params(trader, admin)
                    .update();
            clock.set(NOW);
        }
    }

    /**
     * A tightened stop and a moved take profit, rounded to the tick: the plan answers the new levels with the original
     * ones, one change in the history and the breakeven stop; the row is at version 1. Minute 3 (low 96.9) then stops
     * the position at the new stop 97, sold 10 bps lower: 96.903 down to the tick, 96.90.
     */
    @Test
    void D124_aChange_movesTheLevels_keepsTheOriginals_andTheEngineExitsAtTheNewStop() throws Exception {
        UUID id = openPosition();
        clock.set(minute(2).plusSeconds(30));

        String body = patchLevels(trader, id, "{\"stopLoss\": 97.004, \"takeProfit\": 112}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.status").value("OPEN"))
                .andExpect(jsonPath("$.position.stopWidened").value(false))
                .andExpect(jsonPath("$.position.levelChangeCount").value(1))
                .andExpect(jsonPath("$.position.levelWarnings").isEmpty())
                .andExpect(jsonPath("$.position.levelsEffectiveFrom").value("2026-10-25T08:03:00Z"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(decimal(body, "$.position.stopLoss")).isEqualByComparingTo("97");
        assertThat(decimal(body, "$.position.takeProfit")).isEqualByComparingTo("112");
        assertThat(decimal(body, "$.position.originalStopLoss")).isEqualByComparingTo("95");
        assertThat(decimal(body, "$.position.originalTakeProfit")).isEqualByComparingTo("110");
        assertThat(decimal(body, "$.position.levelChanges[0].oldStopLoss")).isEqualByComparingTo("95");
        assertThat(decimal(body, "$.position.levelChanges[0].newTakeProfit")).isEqualByComparingTo("112");
        assertThat(Instant.parse(JsonPath.read(body, "$.position.levelChanges[0].changedAt")))
                .isEqualTo(minute(2).plusSeconds(30));
        assertThat(decimal(body, "$.position.breakevenPrice"))
                .as("above the entry by the fees and the slippage of a stop-out")
                .isGreaterThan(new BigDecimal("100"));
        assertThat((Boolean) JsonPath.read(body, "$.position.breakevenProvisional"))
                .isFalse();
        Map<String, Object> row = row(id);
        assertThat(row.get("version")).isEqualTo(1L);
        assertDecimal(row, "original_stop_loss_price", "95");
        assertThat(history(id)).hasSize(1);

        feedClosed(2, "100.5", "100", "101");
        feedClosed(3, "100", "96.9", "101");

        Map<String, Object> closed = awaitClosed(id);
        assertThat(closed.get("close_reason")).isEqualTo("STOP_LOSS");
        assertDecimal(closed, "exit_price", "96.90");
        assertThat(((Timestamp) closed.get("exit_time")).toInstant()).isEqualTo(minute(3));
    }

    /**
     * D-122: a moved take profit is a resting limit order: minute 3 opens at 105, beyond the new take profit 104, and
     * the position exits at 104 as a maker: fee 0.04 + 104 × 2 × 0.0002 = 0.0816.
     */
    @Test
    void D122_aMovedTakeProfit_fillsAtItsPriceThroughAGap_asAMaker() throws Exception {
        UUID id = openPosition();
        clock.set(minute(2).plusSeconds(30));
        patchLevels(trader, id, "{\"takeProfit\": 104}").andExpect(status().isOk());

        feedClosed(2, "100.5", "100", "101");
        feedClosed(3, "105", "104.5", "106");

        Map<String, Object> closed = awaitClosed(id);
        assertThat(closed.get("close_reason")).isEqualTo("TAKE_PROFIT");
        assertDecimal(closed, "exit_price", "104");
        assertDecimal(closed, "fee_amount", "0.0816");
    }

    /**
     * BR-42: a stop moved further from the entry than the original sets {@code stop_widened}, which a later move back
     * does not clear; at 93 the position risks 2 × 7 = 14, 1.4 % of 1,000, above BALANCED's 1 %: a warning, stored.
     */
    @Test
    void D124_aWidenedStop_setsTheFlagForGood_andWarnsAboveTheProfile() throws Exception {
        UUID id = openPosition();
        clock.set(minute(2).plusSeconds(10));
        patchLevels(trader, id, "{\"stopLoss\": 93}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.stopWidened").value(true))
                .andExpect(jsonPath("$.position.levelWarnings[0].type").value("OVERSIZED_POSITION"))
                .andExpect(jsonPath("$.position.levelWarnings[0].severity").value("WARNING"));

        clock.set(minute(2).plusSeconds(40));
        patchLevels(trader, id, "{\"stopLoss\": 96}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.stopWidened").value(true))
                .andExpect(jsonPath("$.position.levelChangeCount").value(2))
                .andExpect(jsonPath("$.position.levelWarnings").isEmpty());

        assertThat(history(id))
                .extracting(change -> ((BigDecimal) change.get("new_stop_loss_price")).stripTrailingZeros())
                .containsExactly(new BigDecimal("93"), new BigDecimal("96"));
        assertThat(row(id).get("stop_widened")).isEqualTo(true);
    }

    /** The refusals: 404, MSG01, MSG16 against the last price and the liquidation price, 503 without a price. */
    @Test
    void D124_refusedChanges_answerTheirCodes_andChangeNothing() throws Exception {
        UUID id = openPosition();
        clock.set(minute(2).plusSeconds(30));
        UUID other = account(UserRole.TRADER);

        patchLevels(other, id, "{\"stopLoss\": 97}").andExpect(status().isNotFound());
        patchLevels(trader, id, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stopLoss").value("MSG01"));
        patchLevels(trader, id, "{\"stopLoss\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stopLoss").value("MSG15"));
        patchLevels(trader, id, "{\"stopLoss\": 101, \"takeProfit\": 100}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stopLoss").value("MSG16"))
                .andExpect(jsonPath("$.errors.takeProfit").value("MSG16"));
        BigDecimal liquidation = (BigDecimal) row(id).get("liquidation_price");
        patchLevels(trader, id, "{\"stopLoss\": " + liquidation.toPlainString() + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stopLoss").value("MSG16"));
        lastPrice.set(null);
        patchLevels(trader, id, "{\"stopLoss\": 97}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.messageCode").value("MSG43"));

        assertThat(row(id).get("version")).isEqualTo(0L);
        assertThat(history(id)).isEmpty();
        jdbc.sql("delete from user_account where user_id = ?").param(other).update();
    }

    /** A position closed first, or a plan that never filled, answers 409 MSG43 and nothing changes. */
    @Test
    void D124_noOpenPosition_answersMsg43() throws Exception {
        UUID id = openPosition();
        feedClosed(2, "100", "99", "111");
        Map<String, Object> closed = awaitClosed(id);
        clock.set(minute(3).plusSeconds(30));

        patchLevels(trader, id, "{\"stopLoss\": 97}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG43"));

        assertThat(row(id).get("version")).isEqualTo(closed.get("version"));
        UUID waiting =
                activePlan(PlanFixture.futuresLong().entry("90").stop("85").takeProfit("110"), NOW);
        patchLevels(trader, waiting, "{\"stopLoss\": 86}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG43"));
    }

    /**
     * D-119 (5): an engine exit at the stop 95, decided in minute 3 at version 0, and a change of the stop to 97 at
     * 02:30 race for the row. Exactly one wins: the exit closes it and the change answers MSG43, or the change moves the
     * levels and the exit, decided by levels no longer in force, changes nothing.
     */
    @RepeatedTest(5)
    void D124_aChangeRacingAnEngineExit_hasExactlyOneOutcome() throws Exception {
        UUID id = openPosition();
        clock.set(minute(2).plusSeconds(30));
        feed.stop();
        worker.stop();
        TrackedPosition tracked = matching.openPositions().stream()
                .filter(open -> open.planId().equals(id))
                .findFirst()
                .orElseThrow();
        PositionExit exit = new PositionExit(
                tracked,
                new Exit(id, CloseReason.STOP_LOSS, new BigDecimal("95"), minute(3)),
                Excursion.at(tracked.entryPrice()));
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<Boolean> close = () -> {
            together.await();
            return matching.close(exit);
        };
        Callable<Integer> change = () -> {
            together.await();
            return patchLevels(trader, id, "{\"stopLoss\": 97}")
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };

        boolean closed;
        int changed;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> exiting = pool.submit(close);
            Future<Integer> changing = pool.submit(change);
            closed = exiting.get();
            changed = changing.get();
        }

        Map<String, Object> row = row(id);
        if (closed) {
            assertThat(changed).as("the exit committed first").isEqualTo(409);
            assertThat(row.get("trade_status")).isEqualTo("CLOSED");
            assertThat(row.get("level_change_count")).isEqualTo(0);
            assertThat(history(id)).isEmpty();
        } else {
            assertThat(changed).as("the change committed first").isEqualTo(200);
            assertThat(row.get("trade_status")).isEqualTo("OPEN");
            assertDecimal(row, "stop_loss_price", "97");
            assertThat(history(id)).hasSize(1);
        }
    }

    /**
     * Replay equals live with a change in the middle: the stop moves from 95 to 99 at 02:30. Minute 2 (low 98.6) opened
     * before the change and does not reach 95; minute 3 (low 99.5) reaches nothing; minute 4 (low 98.9) stops the
     * position at 99, sold at 98.90. Live, the change arrives between two updates of minute 2; after a crash, the
     * engine stopped after minute 1, the change is made while it is down, and the restart replays minutes 2 to 4 with
     * the history read from the journal. Both rows end the same.
     */
    @Test
    void D124_aReplayAfterACrash_endsThePositionAsLive_withAChangeInTheMiddle() throws Exception {
        UUID live = openPosition();
        clock.set(minute(2).plusSeconds(10));
        feed.onMinuteKline(forming(2, "100", "99.5", "100.5"));
        clock.set(minute(2).plusSeconds(30));
        lastPrice.set(new BigDecimal("99.8"));
        patchLevels(trader, live, "{\"stopLoss\": 99}").andExpect(status().isOk());
        feedClosed(2, "100", "98.6", "100.5");
        feedClosed(3, "100", "99.5", "101");
        feedClosed(4, "100", "98.9", "101");
        Map<String, Object> liveRow = awaitClosed(live);

        feed.stop();
        worker.stop();
        clock.set(NOW);
        lastPrice.set(new BigDecimal("101"));
        UUID crashed = openPosition();
        feed.stop();
        worker.stop();
        clock.set(minute(2).plusSeconds(30));
        lastPrice.set(new BigDecimal("99.8"));
        patchLevels(trader, crashed, "{\"stopLoss\": 99}").andExpect(status().isOk());
        served.add(closedKline(2, "100", "98.6", "100.5"));
        served.add(closedKline(3, "100", "99.5", "101"));
        served.add(closedKline(4, "100", "98.9", "101"));
        clock.set(minute(5).plusSeconds(10));
        feed.start();
        Map<String, Object> replayedRow = awaitClosed(crashed);

        for (String column : List.of("close_reason", "exit_time", "exit_price", "realized_pnl", "fee_amount")) {
            assertThat(normalised(replayedRow.get(column))).as(column).isEqualTo(normalised(liveRow.get(column)));
        }
        assertThat(liveRow.get("close_reason")).isEqualTo("STOP_LOSS");
        assertDecimal(liveRow, "exit_price", "98.90");
        assertThat(((Timestamp) liveRow.get("exit_time")).toInstant()).isEqualTo(minute(4));
    }

    /**
     * D-79 analogue: a change committed while the pair replays applies from its instant. The replayed minute 2 opened
     * before it and is decided by the earlier stop 95, which its low 94 reaches; so the position ends as it would have
     * live, at 95 sold 10 bps lower (94.90) in minute 2, although the change moved the stop to 97 later.
     */
    @Test
    void D79_aChangeDuringTheReplay_doesNotActOnTheOlderCandles() throws Exception {
        UUID id = openPosition();
        feed.stop();
        worker.stop();
        served.add(closedKline(2, "100", "94", "100.5"));
        served.add(closedKline(3, "100", "99", "101"));
        clock.set(minute(4).plusSeconds(10));
        List<Integer> changes = new CopyOnWriteArrayList<>();
        duringReplay = () -> {
            try {
                changes.add(patchLevels(trader, id, "{\"stopLoss\": 97}")
                        .andReturn()
                        .getResponse()
                        .getStatus());
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        };

        feed.start();

        Map<String, Object> closed = awaitClosed(id);
        assertThat(changes).as("the change was made before any replayed candle").containsExactly(200);
        assertThat(closed.get("close_reason")).isEqualTo("STOP_LOSS");
        assertDecimal(closed, "exit_price", "94.90");
        assertThat(((Timestamp) closed.get("exit_time")).toInstant()).isEqualTo(minute(2));
        assertThat(history(id)).hasSize(1);
    }

    /** A Futures LONG LIMIT 2 at 100 on a new pair, filled by minute 1 as a maker; the engine runs afterwards. */
    private UUID openPosition() {
        insertPair();
        UUID id = activePlan(PlanFixture.futuresLong(), NOW);
        // Minute 0 does not reach the entry; with it the live feed sees no hole to replay.
        feedClosed(0, "100.5", "100.2", "101");
        feedClosed(1, "100.5", "99.5", "101");
        awaitUntil(() -> "OPEN".equals(tradeStatus(id)), "the position of " + id + " opened");
        return id;
    }

    private UUID activePlan(PlanFixture fixture, Instant activatedAt) {
        UUID id = inTransaction(() -> {
                    TradingPlan plan = TradingPlan.draft(
                            trader,
                            pair,
                            fixture.entryType(EntryType.LIMIT).build(),
                            new PlanDetails(
                                    EntryType.LIMIT,
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

    private ResultActions patchLevels(UUID caller, UUID planId, String body) throws Exception {
        return mvc.perform(patch("/api/v1/plans/" + planId + "/position-levels")
                .header(HttpHeaders.AUTHORIZATION, bearer(caller, UserRole.TRADER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Changes {@code SLIPPAGE_BPS} as an administrator does on SCR-43, which evicts the reader's cached value. */
    private void changeSlippage(String basisPoints) throws Exception {
        mvc.perform(put("/api/v1/admin/settings/SLIPPAGE_BPS")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\": \"" + basisPoints + "\"}"))
                .andExpect(status().isOk());
    }

    /** The closed candle of the minute, after the clock moved to its end. */
    private void feedClosed(int minute, String open, String low, String high) {
        if (clock.instant().isBefore(minute(minute + 1))) {
            clock.set(minute(minute + 1));
        }
        feed.onMinuteKline(closedKline(minute, open, low, high));
    }

    private MinuteKline closedKline(int minute, String open, String low, String high) {
        Instant openTime = minute(minute);
        return new MinuteKline(
                MarketType.FUTURES,
                pair,
                openTime,
                new BigDecimal(low),
                new BigDecimal(high),
                new BigDecimal(high),
                true,
                openTime.plusSeconds(60).minusMillis(1),
                new BigDecimal(open));
    }

    private MinuteKline forming(int minute, String open, String low, String high) {
        Instant openTime = minute(minute);
        return new MinuteKline(
                MarketType.FUTURES,
                pair,
                openTime,
                new BigDecimal(low),
                new BigDecimal(high),
                new BigDecimal(high),
                false,
                clock.instant(),
                new BigDecimal(open));
    }

    private Map<String, Object> awaitClosed(UUID planId) {
        awaitUntil(() -> "CLOSED".equals(tradeStatus(planId)), "the journal row of " + planId + " closed");
        return row(planId);
    }

    private String tradeStatus(UUID planId) {
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

    private List<Map<String, Object>> history(UUID planId) {
        return jdbc.sql("""
                        select c.* from trading_journal_level_change c
                          join trading_journal j on j.journal_id = c.journal_id
                         where j.plan_id = ? order by c.changed_at""").param(planId).query().listOfRows();
    }

    private static BigDecimal decimal(String body, String path) {
        Object value = JsonPath.read(body, path);
        return new BigDecimal(String.valueOf(value));
    }

    private static Object normalised(Object value) {
        return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value;
    }

    private static void assertDecimal(Map<String, Object> row, String column, String expected) {
        assertThat((BigDecimal) row.get(column)).as(column).isEqualByComparingTo(expected);
    }

    private static Instant minute(long n) {
        return NOW.plus(Duration.ofMinutes(n));
    }

    private void insertPair() {
        OffsetDateTime at = NOW.atOffset(ZoneOffset.UTC);
        String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
        UUID baseCoin = UUID.randomUUID();
        UUID quoteCoin = UUID.randomUUID();
        pair = UUID.randomUUID();
        for (Object[] coin : new Object[][] {{baseCoin, "PLB" + suffix}, {quoteCoin, "PLQ" + suffix}}) {
            jdbc.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                    .params(coin[0], coin[1], coin[1], at, at)
                    .update();
        }
        jdbc.sql("""
                        insert into crypto_pair (pair_id, base_coin_id, quote_coin_id, symbol, pair_status,
                                                 spot_exchange_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'INACTIVE', 'TRADING', ?, ?)""").params(pair, baseCoin, quoteCoin, "PL" + suffix, at, at).update();
        pairs.add(pair);
        coins.add(baseCoin);
        coins.add(quoteCoin);
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@tpe.invalid", role.name(), now(), now()).update();
        return id;
    }

    /** Waits for the notifications the closed positions of the pair send after their commit, and deletes them. */
    private void deleteNotifications(UUID of) {
        awaitUntil(
                () -> jdbc.sql("""
                                        select count(*) from trading_journal j
                                         where j.pair_id = ? and j.trade_status = 'CLOSED'
                                           and not exists (select 1 from notification n
                                                            where n.source_key = 'POSITION_CLOSED:' || j.journal_id)""").param(of).query(Long.class).single() == 0,
                "the notifications of the closed positions");
        jdbc.sql("""
                        delete from notification where source_key in
                               (select 'POSITION_CLOSED:' || journal_id from trading_journal where pair_id = ?)""").param(of).update();
    }

    /** An access token, timed by the test's clock that the token validator reads. */
    private String bearer(UUID userId, UserRole role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
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

    /** The application clock, moved by the test with the candles it feeds. */
    static final class MovableClock extends Clock {

        private volatile Instant now = NOW;

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableClockConfig {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
