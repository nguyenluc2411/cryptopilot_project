package com.cryptopilot.drawing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.drawing.dto.request.ChartPointRequest;
import com.cryptopilot.drawing.dto.request.CreateChartDrawingRequest;
import com.cryptopilot.drawing.dto.request.CreateChartLayoutRequest;
import com.cryptopilot.drawing.dto.request.DrawingStyleRequest;
import com.cryptopilot.drawing.model.enums.DrawingType;
import com.cryptopilot.drawing.model.enums.LineStyle;
import com.cryptopilot.drawing.service.ChartDrawingService;
import com.cryptopilot.drawing.service.ChartLayoutService;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Two creates at {@code max − 1} against PostgreSQL, each in its own transaction: the module lock lets one count and
 * insert before the other counts, so neither limit is passed (D-63).
 *
 * <p>Nothing is rolled back, because the two transactions must commit: every test writes its own Trader and pair and
 * {@link #removeRows()} deletes them.
 *
 * <p>Rule: D-63; D-149; T-119.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew and
 * phantoms). PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, §13.3.5 "Advisory Locks".
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DrawingLimitConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private ChartDrawingService drawingService;

    @Autowired
    private ChartLayoutService layoutService;

    private UUID trader;
    private UUID pair;

    @BeforeEach
    void insertTraderAndPair() {
        String run = Long.toString(System.nanoTime() % 1_000_000_000L);
        pair = new MarketTestData(sql, Instant.now()).pair("DC" + run + "AUSDT", true, true, "TRADING", "TRADING", 0);
        trader = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(trader, trader + "@t119.invalid", now(), now()).update();
    }

    @AfterEach
    void removeRows() {
        sql.sql("delete from chart_drawing where user_id = ?").param(trader).update();
        sql.sql("delete from chart_layout where user_id = ?").param(trader).update();
        sql.sql("delete from user_account where user_id = ?").param(trader).update();
        new MarketTestData(sql, Instant.now()).remove(pair);
    }

    @RepeatedTest(10)
    void D63_twoDrawingsAt199_onlyOneIsAdded() throws Exception {
        UUID layout = layout(1);
        sql.sql("""
                        insert into chart_drawing (drawing_id, user_id, layout_id, drawing_type, points, style,
                                                   created_at, updated_at)
                        select gen_random_uuid(), ?, ?, 'HORIZONTAL_LINE', '[]'::jsonb, '{}'::jsonb, ?, ?
                          from generate_series(1, ?)""")
                .params(trader, layout, now(), now(), ChartDrawingServiceImpl.MAX_PER_LAYOUT - 1)
                .update();

        List<String> results = concurrently(() -> drawingService.create(trader, trendLine(layout)));

        assertThat(results).containsExactlyInAnyOrder("CREATED", ErrorCode.CHART_DRAWING_LIMIT_REACHED.code());
        assertThat(count("select count(*) from chart_drawing where layout_id = ?", layout))
                .isEqualTo(ChartDrawingServiceImpl.MAX_PER_LAYOUT);
    }

    @RepeatedTest(10)
    void D63_twoLayoutsAt9_onlyOneIsOpened() throws Exception {
        for (int seqNo = 1; seqNo < ChartLayoutServiceImpl.MAX_PER_PAIR; seqNo++) {
            layout(seqNo);
        }

        List<String> results =
                concurrently(() -> layoutService.create(trader, new CreateChartLayoutRequest(pair, null, null)));

        assertThat(results).containsExactlyInAnyOrder("CREATED", ErrorCode.CHART_LAYOUT_LIMIT_REACHED.code());
        assertThat(count("select count(*) from chart_layout where user_id = ?", trader))
                .isEqualTo(ChartLayoutServiceImpl.MAX_PER_PAIR);
        assertThat(count("select count(distinct seq_no) from chart_layout where user_id = ?", trader))
                .isEqualTo(ChartLayoutServiceImpl.MAX_PER_PAIR);
    }

    /** Runs the create twice at the same moment and answers each outcome: CREATED or the refusal's code. */
    private static List<String> concurrently(Callable<?> create) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        return "NOT_STARTED";
                    }
                    try {
                        create.call();
                        return "CREATED";
                    } catch (BusinessException e) {
                        return e.errorCode().code();
                    } catch (RuntimeException e) {
                        // A unique-key or other failure the lock should have prevented: reported, so the test fails.
                        return e.getClass().getSimpleName();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID layout(int seqNo) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into chart_layout (layout_id, user_id, pair_id, seq_no, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?)""").params(id, trader, pair, seqNo, now(), now()).update();
        return id;
    }

    private static CreateChartDrawingRequest trendLine(UUID layout) {
        return new CreateChartDrawingRequest(
                layout,
                DrawingType.TREND_LINE,
                List.of(
                        new ChartPointRequest(Instant.parse("2026-11-10T00:00:00Z"), new BigDecimal("66000")),
                        new ChartPointRequest(Instant.parse("2026-11-20T00:00:00Z"), new BigDecimal("70000"))),
                new DrawingStyleRequest("#2962FF", 2, LineStyle.SOLID, null, false, false, null, null),
                null,
                null,
                null);
    }

    private long count(String query, UUID key) {
        return sql.sql(query).param(key).query(Long.class).single();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
