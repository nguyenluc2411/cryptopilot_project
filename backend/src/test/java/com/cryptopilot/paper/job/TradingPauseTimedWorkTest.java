package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.impl.TradingMarketData;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * M3, the timed work of a paused Futures market (D-158, invariant I5): while the market is closed no funding is settled
 * and no GTD order expires, since the replay that follows may show the position liquidated before the funding time or
 * the order filled before its date; once the market is open again, both run as before, once.
 *
 * <p>The ordering against the replay itself (a position the replay liquidates before the funding time owes nothing of
 * it; a GTD order is not expired while its pair still owes candles before its date) needs the recovery of K6 and is
 * added with K7.
 *
 * <p>Rule: TR-24, TR-44; NSF-03; D-158.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TradingPauseTimedWorkTest {

    private static final String FUTURES = "/api/v1/paper/futures";
    private static final AtomicLong TICKS = new AtomicLong();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PriceCacheService prices;

    @Autowired
    private TradingMarketData trading;

    @Autowired
    private MarketHealthService health;

    @Autowired
    private FuturesFundingJob funding;

    @Autowired
    private FuturesGoodTillJob goodTill;

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "PT" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5,
                               futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 5
                         where pair_id = ?""").param(pair).update();
        sql.sql("""
                        insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                      maintenance_margin_rate, maintenance_amount, updated_at)
                        values (?, 1, 0, 50000, 125, 0.004, 0, ?)""").params(pair, Timestamp.from(Instant.now())).update();
        mark("100");
        trading.recordDepth(new OrderBookDepth(
                MarketType.FUTURES,
                pair,
                TICKS.incrementAndGet(),
                List.of(level("99.9", "5")),
                List.of(level("100", "5")),
                Instant.now()));
    }

    @AfterEach
    void reopenAndRemoveRows() {
        health.endMaintenance(MarketType.FUTURES);
        for (UUID trader : traders) {
            for (String table : List.of(
                    "futures_margin_adjustment",
                    "order_fill",
                    "futures_position",
                    "futures_symbol_config",
                    "futures_account_config",
                    "trade_order",
                    "order_list",
                    "wallet_ledger_entry",
                    "wallet_balance",
                    "equity_snapshot")) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(trader)
                        .update();
            }
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        trading.retainOnly(MarketType.FUTURES, List.of());
        sql.sql("delete from funding_rate_history where pair_id = ?")
                .param(pair)
                .update();
        sql.sql("delete from leverage_bracket where pair_id = ?").param(pair).update();
        sql.sql("delete from order_matching_watermark where pair_id = ?")
                .param(pair)
                .update();
        new MarketTestData(sql, Instant.now()).remove(pair);
        for (String coin : List.of("USDT", "BTC", "ETH", "BNB")) {
            if (!preStoredCoins.contains(coin)) {
                sql.sql("""
                                delete from coin where symbol = ?
                                   and not exists (select 1 from crypto_pair
                                                    where base_coin_id = coin.coin_id or quote_coin_id = coin.coin_id)""").param(coin).update();
            }
        }
    }

    /** TR-24 under I5: a settlement due while Futures is paused is not taken until the market is open again, then once. */
    @Test
    void D158_noFundingIsSettled_whileFuturesIsPaused_thenOnceWhenOpen() throws Exception {
        UUID trader = trader();
        place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "MARKET", "quantity": "1"}""".formatted(symbol)).andExpect(status().isOk());
        Timestamp opened = Timestamp.from(Instant.now().minus(Duration.ofHours(2)));
        sql.sql("update futures_position set opened_at = ? where user_id = ?")
                .params(opened, trader)
                .update();
        sql.sql("update order_fill set traded_at = ? where user_id = ?")
                .params(opened, trader)
                .update();
        sql.sql("""
                        insert into funding_rate_history (pair_id, funding_time, funding_rate, mark_price)
                        values (?, ?, 0.0001, 100)""")
                .params(pair, Timestamp.from(Instant.now().minus(Duration.ofHours(1))))
                .update();

        health.startMaintenance(MarketType.FUTURES, null, null);
        try {
            assertThat(funding.run()).as("settled while paused").isZero();
            assertThat(fundingFees(trader)).isZero();
        } finally {
            health.endMaintenance(MarketType.FUTURES);
        }

        assertThat(funding.run()).isEqualTo(1);
        assertThat(funding.run()).isZero();
        assertThat(fundingFees(trader)).isOne();
    }

    /** TR-44 under I5: a GTD order whose date came while Futures is paused waits; once open, it expires. */
    @Test
    void D158_noGtdOrderExpires_whileFuturesIsPaused_thenItExpiresWhenOpen() throws Exception {
        UUID trader = trader();
        String placed = place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "LIMIT", "timeInForce": "GTD", "price": "90",
                         "quantity": "1", "goodTillDate": "%s"}""".formatted(symbol, Instant.now().plus(Duration.ofHours(1))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID order = UUID.fromString(JsonPath.read(placed, "$.orderId"));
        sql.sql("update trade_order set good_till_date = ? where order_id = ?")
                .params(Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), order)
                .update();

        health.startMaintenance(MarketType.FUTURES, null, null);
        try {
            goodTill.run();
            assertThat(statusOf(order)).as("expired while paused").isEqualTo("NEW");
        } finally {
            health.endMaintenance(MarketType.FUTURES);
        }

        goodTill.run();
        assertThat(statusOf(order)).isEqualTo("EXPIRED");
    }

    private long fundingFees(UUID trader) {
        return sql.sql("select count(*) from wallet_ledger_entry where user_id = ? and entry_type = 'FUNDING_FEE'")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private String statusOf(UUID order) {
        return sql.sql("select order_status from trade_order where order_id = ?")
                .param(order)
                .query(String.class)
                .single();
    }

    private void mark(String mark) {
        BigDecimal value = new BigDecimal(mark);
        prices.record(
                MarketType.FUTURES,
                new MarkPriceMessage(
                        symbol,
                        value,
                        value,
                        BigDecimal.ZERO,
                        Instant.now().plus(Duration.ofHours(1)),
                        Instant.now().plusMillis(TICKS.incrementAndGet())));
        prices.flush();
    }

    private static PriceLevel level(String price, String quantity) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(quantity));
    }

    private ResultActions place(UUID trader, String body) throws Exception {
        return mvc.perform(post(FUTURES + "/order")
                .header(HttpHeaders.AUTHORIZATION, bearer(trader))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UUID trader() throws Exception {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@pause.invalid", now, now).update();
        traders.add(id);
        mvc.perform(post("/api/v1/paper/account").header(HttpHeaders.AUTHORIZATION, bearer(id)))
                .andExpect(status().isCreated());
        return id;
    }

    private String bearer(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
