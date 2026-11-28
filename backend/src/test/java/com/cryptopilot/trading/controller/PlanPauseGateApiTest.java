package com.cryptopilot.trading.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.paper.service.PaperAccountService;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.MarketIncidents;
import com.cryptopilot.support.StreamedPrices;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M1 for trading plans (D-159): on a paused market a plan cannot start waiting, be cancelled while it waits, or have
 * its open position closed or its levels moved, as each of these depends on prices the replay has not caught up on;
 * a draft is created, edited and cancelled as usual, since it touches no price. The other market's pause never refuses
 * any of it.
 *
 * <p>Markets are closed through maintenance, with the health monitor off.
 *
 * <p>Rule: UC-16, UC-17, UC-21, BR-39; NSF-03; D-159.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PlanPauseGateApiTest {

    private static final String PLANS = "/api/v1/plans";

    /** The PRO package of 30 days that V11 seeded. */
    private static final UUID PRO_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000003");

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
    private MarketHealthService health;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();

    @Autowired
    private PaperAccountService paperAccounts;

    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        preStoredCoins.clear();
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "PP" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5,
                               futures_tick_size = 0.01, futures_step_size = 0.001, futures_min_notional = 5
                         where pair_id = ?""").param(pair).update();
        sql.sql("""
                        insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                      maintenance_margin_rate, maintenance_amount, updated_at)
                        values (?, 1, 0, 50000, 125, 0.004, 0, ?)""").params(pair, now()).update();
        StreamedPrices streamed = new StreamedPrices(prices);
        streamed.last(
                MarketType.SPOT, symbol, new BigDecimal("100"), Instant.now().plusMillis(TICKS.incrementAndGet()));
        streamed.last(
                MarketType.FUTURES, symbol, new BigDecimal("100"), Instant.now().plusMillis(TICKS.incrementAndGet()));
        streamed.mark(symbol, new BigDecimal("100"), Instant.now().plusMillis(TICKS.incrementAndGet()));
    }

    @AfterEach
    void reopenAndRemoveRows() {
        health.endMaintenance(MarketType.SPOT);
        health.endMaintenance(MarketType.FUTURES);
        MarketIncidents.removeAll(sql);
        InAppNotifications.awaitDeliveries(sql);
        for (UUID account : accounts) {
            sql.sql("delete from trading_journal_level_change where journal_id in"
                            + " (select journal_id from trading_journal where user_id = ?)")
                    .param(account)
                    .update();
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(account)
                    .update();
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
                        .param(account)
                        .update();
            }
            sql.sql("delete from trading_plan where user_id = ?").param(account).update();
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(account).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from notification where user_id = ?").param(account).update();
            sql.sql("delete from user_profile where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        sql.sql("delete from trading_journal where pair_id = ?").param(pair).update();
        sql.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        sql.sql("delete from order_matching_watermark where pair_id = ?")
                .param(pair)
                .update();
        new MarketTestData(sql, Instant.now()).remove(pair);
        // The seed and schema tests expect no coin they did not write: the coins an opening granted go again.
        for (String coin : List.of("USDT", "BTC", "ETH", "BNB")) {
            if (!preStoredCoins.contains(coin)) {
                sql.sql("delete from coin where symbol = ? and not exists (select 1 from crypto_pair p where"
                                + " p.base_coin_id = coin.coin_id or p.quote_coin_id = coin.coin_id)")
                        .param(coin)
                        .update();
            }
        }
    }

    /** A draft touches no price: created, edited and cancelled while both markets are paused. */
    @ParameterizedTest
    @EnumSource(MarketType.class)
    void D159_aDraft_isCreatedEditedAndCancelled_whileBothMarketsArePaused(MarketType market) throws Exception {
        UUID trader = trader();
        pauseBoth();

        MvcResult created = send(trader, post(PLANS), limit(market, false));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertNotPaused(send(trader, put(PLANS + "/" + id), update(market)), "edit a draft");
        assertNotPaused(send(trader, post(PLANS + "/" + id + "/cancel"), null), "cancel a draft");
    }

    /** Saving a plan to start waiting at once is refused on its paused market only. */
    @ParameterizedTest
    @EnumSource(MarketType.class)
    void D159_savingAPlanActive_isRefusedOnItsPausedMarket(MarketType market) throws Exception {
        UUID trader = trader();

        refusedOn(market, () -> send(trader, post(PLANS), limit(market, true)), "save active");
        passesWhileOtherPaused(market, () -> send(trader, post(PLANS), limit(market, true)), "save active");
    }

    /** Activating a draft is refused on its paused market only. */
    @ParameterizedTest
    @EnumSource(MarketType.class)
    void D159_activatingADraft_isRefusedOnItsPausedMarket(MarketType market) throws Exception {
        UUID trader = trader();
        String id = id(send(trader, post(PLANS), limit(market, false)));

        refusedOn(market, () -> send(trader, post(PLANS + "/" + id + "/activate"), null), "activate");
        passesWhileOtherPaused(market, () -> send(trader, post(PLANS + "/" + id + "/activate"), null), "activate");
    }

    /** Q1 (rule A): a waiting plan is not cancelled on its paused market, as the replay may still fill it. */
    @ParameterizedTest
    @EnumSource(MarketType.class)
    void D159_cancellingAWaitingPlan_isRefusedOnItsPausedMarket(MarketType market) throws Exception {
        UUID trader = trader();
        String id = id(send(trader, post(PLANS), limit(market, true)));

        refusedOn(market, () -> send(trader, post(PLANS + "/" + id + "/cancel"), null), "cancel waiting");
        passesWhileOtherPaused(market, () -> send(trader, post(PLANS + "/" + id + "/cancel"), null), "cancel waiting");
    }

    /** The open position of an executed plan is neither closed nor given new levels on its paused market. */
    @ParameterizedTest
    @EnumSource(MarketType.class)
    void D159_anOpenPosition_isNotClosedOrMoved_onItsPausedMarket(MarketType market) throws Exception {
        UUID trader = trader();
        MvcResult executed = send(trader, post(PLANS), marketEntry(market));
        assertThat((String) JsonPath.read(executed.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("EXECUTED");
        String id = JsonPath.read(executed.getResponse().getContentAsString(), "$.id");

        refusedOn(market, () -> levels(trader, id), "move levels");
        refusedOn(market, () -> send(trader, post(PLANS + "/" + id + "/close-position"), null), "close");
        passesWhileOtherPaused(market, () -> levels(trader, id), "move levels");
        passesWhileOtherPaused(market, () -> send(trader, post(PLANS + "/" + id + "/close-position"), null), "close");
    }

    @FunctionalInterface
    private interface Request {
        MvcResult send() throws Exception;
    }

    private void refusedOn(MarketType market, Request request, String what) throws Exception {
        health.startMaintenance(market, null, null);
        try {
            MvcResult refused = request.send();
            assertThat(refused.getResponse().getStatus())
                    .as("%s on %s while it is paused", what, market)
                    .isEqualTo(503);
            String body = refused.getResponse().getContentAsString();
            assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("TRADING_PAUSED");
            assertThat((String) JsonPath.read(body, "$.market")).isEqualTo(market.name());
        } finally {
            health.endMaintenance(market);
        }
    }

    private void passesWhileOtherPaused(MarketType market, Request request, String what) throws Exception {
        MarketType other = market == MarketType.SPOT ? MarketType.FUTURES : MarketType.SPOT;
        health.startMaintenance(other, null, null);
        try {
            assertNotPaused(request.send(), what + " on " + market + " while " + other + " is paused");
        } finally {
            health.endMaintenance(other);
        }
    }

    private static void assertNotPaused(MvcResult result, String what) throws Exception {
        assertThat(result.getResponse().getStatus()).as(what).isLessThan(400);
    }

    private void pauseBoth() {
        health.startMaintenance(MarketType.SPOT, null, null);
        health.startMaintenance(MarketType.FUTURES, null, null);
    }

    private MvcResult levels(UUID trader, String id) throws Exception {
        return send(trader, patch(PLANS + "/" + id + "/position-levels"), "{\"takeProfit\": 112}");
    }

    /** A LONG LIMIT at 99 (SL 95, TP 110) below the last price of 100, so it waits once active. */
    private String limit(MarketType market, boolean activate) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"" + market + "\", \"direction\": \"LONG\","
                + " \"entryType\": \"LIMIT\", \"entryPrice\": 99, \"stopLoss\": 95, \"takeProfit\": 110"
                + (market == MarketType.FUTURES ? ", \"leverage\": 5" : "")
                + ", \"activate\": " + activate + "}";
    }

    private static String update(MarketType market) {
        return "{\"market\": \"" + market + "\", \"direction\": \"LONG\", \"entryType\": \"LIMIT\","
                + " \"entryPrice\": 98, \"stopLoss\": 95, \"takeProfit\": 110"
                + (market == MarketType.FUTURES ? ", \"leverage\": 5" : "") + "}";
    }

    /** A LONG MARKET (SL 95, TP 110) saved active, so it executes at the last price of 100. */
    private String marketEntry(MarketType market) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"" + market + "\", \"direction\": \"LONG\","
                + " \"entryType\": \"MARKET\", \"stopLoss\": 95, \"takeProfit\": 110"
                + (market == MarketType.FUTURES ? ", \"leverage\": 5" : "")
                + ", \"activate\": true}";
    }

    private static String id(MvcResult created) throws Exception {
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private MvcResult send(UUID trader, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    /** A PRO Trader on BALANCED with a default capital of 1,000. */
    private UUID trader() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@pause.invalid", now(), now()).update();
        accounts.add(id);
        sql.sql("""
                        insert into user_profile (user_id, display_name, default_capital, risk_profile, created_at,
                                                  updated_at)
                        values (?, 'Trader', 1000, 'BALANCED', ?, ?)""").params(id, now(), now()).update();
        subscribeToPro(id);
        // RF-33: an activated plan is carried out on the demo account (BR-68).
        paperAccounts.open(id);
        return id;
    }

    private void subscribeToPro(UUID trader) {
        Instant start = Instant.now().minus(Duration.ofDays(1));
        UUID order = UUID.randomUUID();
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 1, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        trader,
                        PRO_PACKAGE,
                        "PP-" + order,
                        Timestamp.from(start),
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(900)),
                        Timestamp.from(start))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(start),
                        Timestamp.from(start.plus(Duration.ofDays(30))),
                        Timestamp.from(start),
                        Timestamp.from(start))
                .update();
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
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
