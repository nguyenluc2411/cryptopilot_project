package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.event.MarketPauseAnnounced;
import com.cryptopilot.market.event.MarketTradingResumed;
import com.cryptopilot.market.model.enums.IncidentReason;
import com.cryptopilot.market.model.enums.MarketHealthState;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.impl.TradingMarketData;
import com.cryptopilot.notification.service.MarketNotificationService;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * K9 end to end (D-159), through the event publication registry: maintenance of Futures tells a Trader with a waiting
 * Futures order that trading is paused, with the administrator's message, and tells the administrators; ending it
 * tells the Trader it reopened and that nothing of theirs changed. A Trader with nothing on the market hears nothing.
 *
 * <p>Rule: NSF-03; NSF-15; D-159.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MarketIncidentNoticeApiTest {

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
    private MarketNotificationService broadcasts;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "PN" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        sql.sql("""
                        update crypto_pair set futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 5
                         where pair_id = ?""").param(pair).update();
        sql.sql("""
                        insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                      maintenance_margin_rate, maintenance_amount, updated_at)
                        values (?, 1, 0, 50000, 125, 0.004, 0, ?)""").params(pair, Timestamp.from(Instant.now())).update();
        BigDecimal mark = new BigDecimal("100");
        prices.record(
                MarketType.FUTURES,
                new MarkPriceMessage(
                        symbol,
                        mark,
                        mark,
                        BigDecimal.ZERO,
                        Instant.now().plus(Duration.ofHours(1)),
                        Instant.now().plusMillis(TICKS.incrementAndGet())));
        prices.flush();
        trading.recordDepth(new OrderBookDepth(
                MarketType.FUTURES,
                pair,
                TICKS.incrementAndGet(),
                List.of(new PriceLevel(new BigDecimal("99.9"), new BigDecimal("5"))),
                List.of(new PriceLevel(new BigDecimal("100"), new BigDecimal("5"))),
                Instant.now()));
    }

    @AfterEach
    void reopenAndRemoveRows() {
        health.endMaintenance(MarketType.FUTURES);
        awaitDeliveries();
        List<UUID> incidents = sql.sql("select market_incident_id from market_incident")
                .query(UUID.class)
                .list();
        for (UUID incident : incidents) {
            sql.sql("delete from notification where source_key like ?")
                    .param("MARKET_%" + incident + "%")
                    .update();
        }
        sql.sql("delete from market_incident").update();
        for (UUID account : accounts) {
            sql.sql("delete from notification where user_id = ?").param(account).update();
            for (String table : List.of(
                    "trading_journal",
                    "order_fill",
                    "futures_position",
                    "futures_symbol_config",
                    "futures_account_config",
                    "trade_order",
                    "wallet_ledger_entry",
                    "wallet_balance",
                    "equity_snapshot")) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(account)
                        .update();
            }
            // Paper notices (TR-29) and their pushes are written after the commit: awaited first.
            InAppNotifications.remove(sql, List.of(account));
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        trading.retainOnly(MarketType.FUTURES, List.of());
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

    @Test
    void D159_maintenance_tellsTheTradersItAffects_andTheAdministrators_thenTheReopening() throws Exception {
        UUID waiting = trader();
        UUID bystander = trader();
        UUID admin = account(UserRole.ADMIN);
        mvc.perform(post(FUTURES + "/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer(waiting, UserRole.TRADER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symbol": "%s", "side": "BUY", "type": "LIMIT", "timeInForce": "GTC", "price": "90",
                                 "quantity": "1"}""".formatted(symbol)))
                .andExpect(status().isOk());

        health.startMaintenance(MarketType.FUTURES, "Back at 22:00.", admin);

        Map<String, Object> paused = awaitNotice(waiting, "Futures trading paused");
        assertThat(paused.get("notification_type")).isEqualTo("MARKET");
        assertThat((String) paused.get("content")).contains("under maintenance: Back at 22:00.");
        assertThat(awaitNotice(admin, "Futures trading paused").get("notification_type"))
                .isEqualTo("SYSTEM");

        health.endMaintenance(MarketType.FUTURES);

        Map<String, Object> resumed = awaitNotice(waiting, "Futures trading resumed");
        assertThat((String) resumed.get("content")).contains("None of your orders or positions changed meanwhile.");
        awaitDeliveries();
        assertThat(sql.sql("select count(*) from notification where user_id = ?")
                        .param(bystander)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(sql.sql("select count(*) from notification where user_id = ? and notification_type = 'MARKET'")
                        .param(waiting)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        // N2, N3: the general notices, written later, reach the bystander alone, in the bell.
        Map<String, Object> incident = sql.sql(
                        "select market_incident_id, last_live_at, resumed_at from market_incident"
                                + " where market_type = 'FUTURES' order by paused_at desc limit 1")
                .query()
                .singleRow();
        UUID id = (UUID) incident.get("market_incident_id");
        Instant lastLive = ((java.sql.Timestamp) incident.get("last_live_at")).toInstant();
        Instant reopened = ((java.sql.Timestamp) incident.get("resumed_at")).toInstant();
        broadcasts.broadcastPause(new MarketPauseAnnounced(
                MarketType.FUTURES,
                id,
                MarketHealthState.MAINTENANCE,
                IncidentReason.MAINTENANCE,
                lastLive,
                "Back at 22:00."));
        broadcasts.broadcastResume(new MarketTradingResumed(MarketType.FUTURES, id, lastLive, reopened, true));
        assertThat(sql.sql("select source_key from notification where user_id = ? order by source_key")
                        .param(bystander)
                        .query(String.class)
                        .list())
                .containsExactly(
                        "MARKET_PAUSED_ALL:" + id + ":" + bystander, "MARKET_RESUMED_ALL:" + id + ":" + bystander);
        assertThat(sql.sql("select count(*) from notification where user_id = ? and source_key like '%ALL:%'")
                        .param(waiting)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private Map<String, Object> awaitNotice(UUID user, String title) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> sql.sql("select count(*) from notification where user_id = ? and title = ?")
                                .params(user, title)
                                .query(Long.class)
                                .single()
                        == 1);
        return sql.sql("select * from notification where user_id = ? and title = ?")
                .params(user, title)
                .query()
                .singleRow();
    }

    private void awaitDeliveries() {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> sql.sql("""
                                        select count(*) from event_publication
                                         where (listener_id like '%IncidentNotices%'
                                                or listener_id like '%MarketNotificationListener%')
                                           and completion_date is null""").query(Long.class).single() == 0);
    }

    private UUID trader() throws Exception {
        UUID id = account(UserRole.TRADER);
        mvc.perform(post("/api/v1/paper/account").header(HttpHeaders.AUTHORIZATION, bearer(id, UserRole.TRADER)))
                .andExpect(status().isCreated());
        return id;
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@notice.invalid", role.name(), now, now).update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId, UserRole role) {
        Instant now = Instant.now();
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
}
