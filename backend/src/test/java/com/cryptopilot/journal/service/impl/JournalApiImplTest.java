package com.cryptopilot.journal.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.journal.JournalApi;
import com.cryptopilot.journal.PerformanceDigest;
import com.cryptopilot.journal.TradeRow;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The digest the AI Assistant reads (T-074, D-111): the dashboard's default trades, as aggregates, through the real
 * statement.
 *
 * <p>Rule: BR-42, BR-51, BR-70; D-111.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class JournalApiImplTest {

    @Autowired
    private JournalApi journal;

    @Autowired
    private JdbcClient sql;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID pair;
    private UUID trader;
    private Instant exit;

    @BeforeEach
    void setUp() {
        pair = new MarketTestData(sql, Instant.now())
                .pair(
                        "JA" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT",
                        true,
                        true,
                        "TRADING",
                        "TRADING",
                        0);
        trader = account();
        exit = Instant.now().minus(2, ChronoUnit.DAYS);
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
        new MarketTestData(sql, Instant.now()).remove(pair);
    }

    @Test
    void D111_theDigest_countsTheTradersClosedSimulatedTrades_withTheLearningMetricsInR() {
        TradeRow.closed(trader, pair, exit)
                .simulated(plan(trader))
                .stop("90")
                .amounts("20", "1")
                .deviation("0.1")
                .excursions("95", "125")
                .insert(sql);
        TradeRow.closed(trader, pair, exit.plusSeconds(60))
                .simulated(plan(trader))
                .stop("90")
                .amounts("-10", "1")
                .deviation("0.3")
                .excursions("88", "104")
                .insert(sql);
        TradeRow.closed(trader, pair, exit).amounts("50", "0").insert(sql);
        UUID other = account();
        TradeRow.closed(other, pair, exit)
                .simulated(plan(other))
                .amounts("70", "0")
                .insert(sql);

        PerformanceDigest digest = journal.performanceDigest(trader);

        assertThat(digest.trades()).isEqualTo(2);
        assertThat(digest.wins()).isEqualTo(1);
        assertThat(digest.losses()).isEqualTo(1);
        assertThat(digest.winRate()).isEqualByComparingTo("0.5");
        assertThat(digest.totalNetPnl()).isEqualByComparingTo("8");
        assertThat(digest.totalFees()).isEqualByComparingTo("2");
        assertThat(digest.profitFactor()).isEqualByComparingTo("1.72727273");
        assertThat(digest.averageRealizedR()).as("(1.9 − 1.1) / 2").isEqualByComparingTo("0.4");
        assertThat(digest.tradesWithR()).isEqualTo(2);
        assertThat(digest.averageEntryDeviationR()).isEqualByComparingTo("0.2");
        assertThat(digest.costOfLateEntryR()).isEqualByComparingTo("0.4");
        assertThat(digest.averageMaeR()).as("(0.5 + 1.2) / 2").isEqualByComparingTo("0.85");
        assertThat(digest.averageMfeR()).as("(2.5 + 0.4) / 2").isEqualByComparingTo("1.45");
    }

    @Test
    void D111_aTraderWithoutAClosedTrade_getsAnEmptyDigest_withoutLearningMetrics() {
        TradeRow.open(trader, pair, exit).simulated(plan(trader)).insert(sql);

        PerformanceDigest digest = journal.performanceDigest(trader);

        assertThat(digest.trades()).isZero();
        assertThat(digest.totalNetPnl()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(digest.winRate()).isNull();
        assertThat(digest.averageRealizedR()).isNull();
        assertThat(digest.averageMaeR()).isNull();
    }

    private UUID plan(UUID owner) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(exit.minus(1, ChronoUnit.DAYS));
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, analysis_timeframe, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 100, 90, 110, 1000, 1, 'DRAFT', '1h', ?, ?)""").params(id, owner, pair, at, at).update();
        return id;
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@journal-api.invalid", at, at).update();
        accounts.add(id);
        return id;
    }
}
