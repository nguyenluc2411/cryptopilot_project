package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The paper Futures settings end to end: defaults, leverage and margin type changes with the exchange's refusals and
 * codes, the brackets, and the Futures feature of the plan. Positions and open orders are written as rows, since
 * placing Futures orders comes with TR-15.
 *
 * <p>Rule: TR-14; D-127; Q-T10.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class FuturesConfigApiTest {

    private static final String FUTURES = "/api/v1/paper/futures";

    /** The PRO package of 30 days that V11 seeded, which has the Futures feature. */
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

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "FC" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5,
                               futures_tick_size = 0.01, futures_step_size = 0.001, futures_min_notional = 5
                         where pair_id = ?""").param(pair).update();
        bracket(pair, 1, "0", "50000", 125, "0.004", "0");
        bracket(pair, 2, "50000", "600000", 100, "0.005", "50");
        bracket(pair, 3, "600000", "3000000", 75, "0.0065", "950");
    }

    @AfterEach
    void removeRows() {
        for (UUID trader : traders) {
            for (String table : List.of(
                    "futures_margin_adjustment",
                    "futures_position",
                    "futures_symbol_config",
                    "futures_account_config",
                    "trade_order",
                    "wallet_ledger_entry",
                    "wallet_balance",
                    "equity_snapshot")) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(trader)
                        .update();
            }
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(trader).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(trader)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        sql.sql("delete from leverage_bracket where pair_id = ?").param(pair).update();
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

    // ------------------------------------------------------------------------------------------
    // Access and defaults
    // ------------------------------------------------------------------------------------------

    @Test
    void aTraderOnTheFreePlan_setsUpFutures_asOnSpot() throws Exception {
        UUID trader = trader(false);

        as(trader, get(FUTURES + "/symbolConfig").param("symbol", symbol), null).andExpect(status().isOk());
        as(trader, post(FUTURES + "/leverage"), leverage(10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leverage").value(10));
    }

    @Test
    void TR14_anUnconfiguredPair_trades20xCrossed_inOneWayMode() throws Exception {
        UUID trader = trader(true);

        as(trader, get(FUTURES + "/symbolConfig").param("symbol", symbol.toLowerCase()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value(symbol))
                .andExpect(jsonPath("$[0].marginType").value("CROSSED"))
                .andExpect(jsonPath("$[0].leverage").value(20))
                .andExpect(jsonPath("$[0].isAutoAddMargin").value(false))
                .andExpect(jsonPath("$[0].maxNotionalValue").value("3000000.00000000"));
        as(trader, get(FUTURES + "/symbolConfig"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        as(trader, get(FUTURES + "/positionSide/dual"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dualSidePosition").value(false));
        as(trader, get(FUTURES + "/accountConfig"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canTrade").value(true))
                .andExpect(jsonPath("$.multiAssetsMargin").value(false))
                .andExpect(jsonPath("$.feeTier").value(0));
    }

    @Test
    void TR14_theBrackets_carryTheirMaintenanceAmounts() throws Exception {
        UUID trader = trader(true);

        as(trader, get(FUTURES + "/leverageBracket").param("symbol", symbol), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value(symbol))
                .andExpect(jsonPath("$.brackets.length()").value(3))
                .andExpect(jsonPath("$.brackets[1].bracket").value(2))
                .andExpect(jsonPath("$.brackets[1].initialLeverage").value(100))
                .andExpect(jsonPath("$.brackets[1].notionalFloor").value("50000.00000000"))
                .andExpect(jsonPath("$.brackets[1].cum").value("50.00000000"));
    }

    @Test
    void TR14_anUnknownSymbol_isNotFound_andAPairWithoutBrackets_isNotTraded() throws Exception {
        UUID trader = trader(true);

        as(trader, get(FUTURES + "/symbolConfig").param("symbol", "NOPEUSDT"), null)
                .andExpect(status().isNotFound());
        sql.sql("delete from leverage_bracket where pair_id = ?").param(pair).update();
        as(trader, post(FUTURES + "/leverage"), leverage(10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAIR_NOT_TRADING"))
                .andExpect(jsonPath("$.binanceCode").value(-4141));
    }

    @Test
    void TR14_aTraderWithoutAnAccount_isToldToOpenOne() throws Exception {
        UUID trader = traderWithoutAccount();
        subscribeToPro(trader);

        as(trader, get(FUTURES + "/positionSide/dual"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
    }

    // ------------------------------------------------------------------------------------------
    // Leverage
    // ------------------------------------------------------------------------------------------

    @Test
    void TR14_theLeverage_changesWithinTheBrackets() throws Exception {
        UUID trader = trader(true);

        as(trader, post(FUTURES + "/leverage"), leverage(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value(symbol))
                .andExpect(jsonPath("$.leverage").value(100))
                .andExpect(jsonPath("$.maxNotionalValue").value("600000.00000000"));
        as(trader, get(FUTURES + "/symbolConfig").param("symbol", symbol), null)
                .andExpect(jsonPath("$[0].leverage").value(100));
        as(trader, get(FUTURES + "/symbolConfig"), null)
                .andExpect(jsonPath("$[0].symbol").value(symbol));
    }

    @Test
    void TR14_aLeverageOutsideTheBrackets_isRefusedWith4028() throws Exception {
        UUID trader = trader(true);

        for (int leverage : new int[] {0, 126}) {
            as(trader, post(FUTURES + "/leverage"), leverage(leverage))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PAPER_LEVERAGE"))
                    .andExpect(jsonPath("$.binanceCode").value(-4028))
                    .andExpect(jsonPath("$.binanceMsg").value("Leverage " + leverage + " is not valid"));
        }
        as(trader, post(FUTURES + "/leverage"), "{\"symbol\": \"" + symbol + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void TR14_aHigherLeverage_isRefusedWhenThePositionIsAboveItsMaximumNotional() throws Exception {
        UUID trader = trader(true);
        markPrice("60000");
        position(trader, "CROSSED", 20, "10", "60000", null);

        // 10 × 60,000 = 600,000: above the 50,000 that 110x allows.
        as(trader, post(FUTURES + "/leverage"), leverage(110))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_MAX_POSITION_AT_LEVERAGE"))
                .andExpect(jsonPath("$.binanceCode").value(-2027));
    }

    @Test
    void TR14_anIsolatedPositionsLeverage_isNeverLowered_butMayBeRaised() throws Exception {
        UUID trader = trader(true);
        markPrice("60000");
        configure(trader, 10, "ISOLATED");
        UUID position = position(trader, "ISOLATED", 10, "0.1", "60000", "600");
        long version = accountVersion(trader);

        as(trader, post(FUTURES + "/leverage"), leverage(5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_LEVERAGE_MARGIN"))
                .andExpect(jsonPath("$.binanceCode").value(-4161));
        as(trader, post(FUTURES + "/leverage"), leverage(20)).andExpect(status().isOk());

        assertThat(positionLeverage(position)).isEqualTo(20);
        assertThat(sql.sql("select isolated_wallet_amount from futures_position where position_id = ?")
                        .param(position)
                        .query(BigDecimal.class)
                        .single())
                .as("the isolated wallet stays as it was")
                .isEqualByComparingTo("600");
        assertThat(accountVersion(trader)).as("the account version rises").isGreaterThan(version);
    }

    @Test
    void TR14_aCrossPositionsLeverage_isLoweredOnlyWhileTheMarginCoversIt() throws Exception {
        UUID trader = trader(true);
        markPrice("60000");
        UUID position = position(trader, "CROSSED", 20, "1", "60000", null);

        // Wallet 5,000; IM 3,000 at 20x. 15x needs 4,000 (1,000 left); 10x needs 6,000 (1,000 short).
        as(trader, post(FUTURES + "/leverage"), leverage(10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-2028));
        as(trader, post(FUTURES + "/leverage"), leverage(15)).andExpect(status().isOk());

        assertThat(positionLeverage(position)).isEqualTo(15);
    }

    @Test
    void TR14_withAnOpenPositionAndNoMarkPrice_theLeverageWaits() throws Exception {
        UUID trader = trader(true);
        position(trader, "CROSSED", 20, "1", "60000", null);

        as(trader, post(FUTURES + "/leverage"), leverage(15))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_PRICE_UNAVAILABLE"));
    }

    // ------------------------------------------------------------------------------------------
    // Margin type
    // ------------------------------------------------------------------------------------------

    @Test
    void TR14_theMarginType_changesOnceAndTheSameTypeIsRefusedWith4046() throws Exception {
        UUID trader = trader(true);

        as(trader, post(FUTURES + "/marginType"), marginType("ISOLATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marginType").value("ISOLATED"))
                .andExpect(jsonPath("$.leverage").value(20));
        as(trader, post(FUTURES + "/marginType"), marginType("ISOLATED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_MARGIN_TYPE"))
                .andExpect(jsonPath("$.binanceCode").value(-4046))
                .andExpect(jsonPath("$.binanceMsg").value("No need to change margin type."));
        as(trader, post(FUTURES + "/marginType"), marginType("SOMETHING")).andExpect(status().isBadRequest());
    }

    @Test
    void TR14_aPairWithAnOpenOrder_keepsItsMarginType() throws Exception {
        UUID trader = trader(true);
        openOrder(trader);

        as(trader, post(FUTURES + "/marginType"), marginType("ISOLATED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-4047));
    }

    @Test
    void TR14_aPairWithAPosition_keepsItsMarginType() throws Exception {
        UUID trader = trader(true);
        position(trader, "CROSSED", 20, "0.1", "60000", null);

        as(trader, post(FUTURES + "/marginType"), marginType("ISOLATED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-4048));
    }

    @Test
    void TR23_whileACrossLiquidationRuns_nothingChanges() throws Exception {
        UUID trader = trader(true);
        as(trader, get(FUTURES + "/accountConfig"), null).andExpect(status().isOk());
        sql.sql("update futures_account_config set liquidating_since = now() where user_id = ?")
                .param(trader)
                .update();

        as(trader, post(FUTURES + "/leverage"), leverage(10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_USER_IN_LIQUIDATION"))
                .andExpect(jsonPath("$.binanceCode").value(-2023));
        as(trader, post(FUTURES + "/marginType"), marginType("ISOLATED"))
                .andExpect(jsonPath("$.binanceCode").value(-2023));
        as(trader, get(FUTURES + "/accountConfig"), null)
                .andExpect(jsonPath("$.canTrade").value(false))
                .andExpect(jsonPath("$.canWithdraw").value(false));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private String leverage(int leverage) {
        return "{\"symbol\": \"" + symbol + "\", \"leverage\": " + leverage + "}";
    }

    private String marginType(String type) {
        return "{\"symbol\": \"" + symbol + "\", \"marginType\": \"" + type + "\"}";
    }

    private void bracket(UUID pairId, int no, String floor, String cap, int leverage, String mmr, String cum) {
        sql.sql("""
                        insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                      maintenance_margin_rate, maintenance_amount, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        pairId,
                        no,
                        new BigDecimal(floor),
                        new BigDecimal(cap),
                        leverage,
                        new BigDecimal(mmr),
                        new BigDecimal(cum),
                        Timestamp.from(Instant.now()))
                .update();
    }

    private void configure(UUID trader, int leverage, String marginType) {
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into futures_symbol_config (symbol_config_id, user_id, pair_id, leverage, margin_type,
                                                           created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), trader, pair, leverage, marginType, now, now)
                .update();
    }

    private UUID position(
            UUID trader, String marginType, int leverage, String quantity, String entry, String isolatedWallet) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into futures_position (position_id, user_id, pair_id, position_side, direction,
                                                      margin_type, leverage, quantity, max_quantity, entry_price,
                                                      isolated_wallet_amount, position_status, opened_at, created_at,
                                                      updated_at)
                        values (?, ?, ?, 'BOTH', 'LONG', ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)""")
                .params(
                        id,
                        trader,
                        pair,
                        marginType,
                        leverage,
                        new BigDecimal(quantity),
                        new BigDecimal(quantity),
                        new BigDecimal(entry),
                        isolatedWallet == null ? null : new BigDecimal(isolatedWallet),
                        now,
                        now,
                        now)
                .update();
        return id;
    }

    private void openOrder(UUID trader) {
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into trade_order (order_id, user_id, pair_id, market_type, client_order_id, side,
                                                 position_side, order_type, time_in_force, limit_price, orig_quantity,
                                                 executed_quantity, cum_quote_amount, reduce_only, close_position,
                                                 order_status, created_at, updated_at)
                        values (?, ?, ?, 'FUTURES', ?, 'BUY', 'BOTH', 'LIMIT', 'GTC', 50000, 0.1, 0, 0, false, false,
                                'NEW', ?, ?)""")
                .params(UUID.randomUUID(), trader, pair, "fc-" + TICKS.incrementAndGet(), now, now)
                .update();
    }

    private int positionLeverage(UUID position) {
        return sql.sql("select leverage from futures_position where position_id = ?")
                .param(position)
                .query(Integer.class)
                .single();
    }

    private long accountVersion(UUID trader) {
        return sql.sql("select version from futures_account_config where user_id = ?")
                .param(trader)
                .query(Long.class)
                .optional()
                .orElse(-1L);
    }

    /** Streams a Futures mark price of the test pair into the price cache, newer than every earlier one. */
    private void markPrice(String mark) {
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

    /** A Trader with an opened paper account, on the PRO plan when {@code futures}. */
    private UUID trader(boolean futures) throws Exception {
        UUID id = traderWithoutAccount();
        if (futures) {
            subscribeToPro(id);
        }
        as(id, post("/api/v1/paper/account"), null).andExpect(status().isCreated());
        return id;
    }

    private UUID traderWithoutAccount() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@tr14.invalid", now, now).update();
        traders.add(id);
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
                        "TR14-" + order,
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

    private ResultActions as(UUID trader, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
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
