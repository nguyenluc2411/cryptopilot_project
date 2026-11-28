package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.paper.job.PaperMatchingWorker;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * Activating a plan places its paper orders (RF-33, D-117: one engine): a MARKET entry fills at once on the demo
 * account and makes the plan EXECUTED, a LIMIT entry waits in the paper book and goes when the plan is cancelled, and a
 * refusal of the order paths leaves nothing stored.
 *
 * <p>The Trader plans with a capital of 1,000 at the BALANCED 1 %: 10 at risk over a stop 5 below an entry of 100 is a
 * quantity of 2. The demo account opens with 10,000 USDT on Spot and 5,000 on Futures.
 *
 * <p>Rule: BR-33, BR-68, BR-71; D-117; RF-33; BM04 WF-01, DS-01a.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PlanPaperOrdersApiTest {

    private static final String PLANS = "/api/v1/plans";
    private static final List<String> GRANTED = List.of("USDT", "BTC", "ETH", "BNB");
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
    private StringRedisTemplate redis;

    @Autowired
    private PaperMatchingWorker paperWorker;

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
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
    }

    @AfterEach
    void removeRows() {
        InAppNotifications.awaitDeliveries(sql);
        for (UUID trader : traders) {
            sql.sql("""
                            delete from trading_journal_level_change where journal_id in
                                   (select journal_id from trading_journal where user_id = ?)""").param(trader).update();
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(trader)
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
                        .param(trader)
                        .update();
            }
            sql.sql("delete from trading_plan where user_id = ?").param(trader).update();
            InAppNotifications.remove(sql, List.of(trader));
            sql.sql("delete from user_profile where user_id = ?").param(trader).update();
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        sql.sql("delete from order_matching_watermark where pair_id = ?")
                .param(pair)
                .update();
        UUID base = sql.sql("select base_coin_id from crypto_pair where pair_id = ?")
                .param(pair)
                .query(UUID.class)
                .single();
        new MarketTestData(sql, Instant.now()).remove(pair);
        sql.sql("delete from coin where coin_id = ?").param(base).update();
        for (String coin : GRANTED) {
            if (!preStoredCoins.contains(coin)) {
                sql.sql("delete from coin where symbol = ?").param(coin).update();
            }
        }
        Set<String> keys = redis.keys("px:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * DS-01a on Spot: activated with the last price at 100, the plan's MARKET buy of 2 fills at once, the take profit
     * and stop loss wait as an OCO sale of what it bought, the plan is EXECUTED and the fill is in the journal as the
     * plan's trade.
     */
    @Test
    void RF33_aSpotMarketPlan_fillsOnTheDemoAccount_andIsExecuted() throws Exception {
        UUID trader = traderWithAccount();
        spotLast("100");

        String created = body(as(trader, post(PLANS), spot("MARKET", null, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED")));
        UUID plan = UUID.fromString(JsonPath.read(created, "$.id"));

        List<Map<String, Object>> orders = ordersOf(plan);
        assertThat(orders)
                .extracting(row -> row.get("order_type"))
                .containsExactly("MARKET", "LIMIT_MAKER", "STOP_LOSS");
        assertThat(orders).extracting(row -> row.get("order_status")).containsExactly("FILLED", "NEW", "NEW");
        assertThat((BigDecimal) orders.getFirst().get("executed_quantity")).isEqualByComparingTo("2");
        assertThat(spot(trader, "USDT")).as("2 bought at 100").isEqualByComparingTo("9800");
        assertThat(spot(trader, base())).as("2 less the 0.1 % fee").isEqualByComparingTo("1.998");
        assertThat(sql.sql("select source from trading_journal where plan_id = ?")
                        .param(plan)
                        .query(String.class)
                        .list())
                .containsExactly("SIMULATED");
    }

    /**
     * A LIMIT plan below the price waits: its OTOCO is in the paper book with the cost locked, the plan ACTIVE. Cancelled,
     * the plan takes its orders with it and the cost is free again (BR-68).
     */
    @Test
    void RF33_aSpotLimitPlan_waitsInThePaperBook_andGoesWithThePlan() throws Exception {
        UUID trader = traderWithAccount();
        spotLast("105");

        String created = body(as(trader, post(PLANS), spot("LIMIT", "100", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        UUID plan = UUID.fromString(JsonPath.read(created, "$.id"));
        assertThat(ordersOf(plan))
                .extracting(row -> row.get("order_status"))
                .containsExactly("NEW", "PENDING_NEW", "PENDING_NEW");
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("200");

        as(trader, post(PLANS + "/" + plan + "/cancel"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(ordersOf(plan)).extracting(row -> row.get("order_status")).containsOnly("CANCELED");
        assertThat(locked(trader, "USDT")).isZero();
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("10000");
    }

    /** A Futures LIMIT plan waits as a LIMIT order with the plan's leverage on isolated margin, and goes with it. */
    @Test
    void RF33_aFuturesLimitPlan_waitsWithItsLeverage_andGoesWithThePlan() throws Exception {
        UUID trader = traderWithAccount();
        futuresLast("105");

        String created = body(as(trader, post(PLANS), futuresLimit())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        UUID plan = UUID.fromString(JsonPath.read(created, "$.id"));
        assertThat(ordersOf(plan))
                .as("the LIMIT entry works; its take profit and stop loss wait on it")
                .extracting(row -> row.get("order_type") + " " + row.get("order_status"))
                .containsExactlyInAnyOrder("LIMIT NEW", "TAKE_PROFIT_MARKET PENDING_NEW", "STOP_MARKET PENDING_NEW");
        assertThat(sql.sql("select leverage || ' ' || margin_type from futures_symbol_config where user_id = ?")
                        .param(trader)
                        .query(String.class)
                        .single())
                .isEqualTo("5 ISOLATED");

        as(trader, post(PLANS + "/" + plan + "/cancel"), null).andExpect(status().isOk());

        assertThat(ordersOf(plan))
                .extracting(row -> row.get("order_status"))
                .doesNotContain("NEW", "PENDING_NEW", "PARTIALLY_FILLED");
    }

    /**
     * A Futures STOP plan waits as a STOP_MARKET conditional order. A trade through the entry triggers it; the MARKET
     * order it places carries the plan, so the fill makes the plan EXECUTED and opens its journal record.
     */
    @Test
    void RF33_aFuturesStopPlan_isExecutedWhenItsStopTriggers() throws Exception {
        UUID trader = traderWithAccount();
        futuresLast("100");
        String created = body(as(trader, post(PLANS), futuresStop())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        UUID plan = UUID.fromString(JsonPath.read(created, "$.id"));

        futuresLast("102.5");
        paperWorker.onTrade(new MarketTrade(
                MarketType.FUTURES,
                pair,
                TICKS.incrementAndGet(),
                new BigDecimal("102.5"),
                BigDecimal.ONE,
                Instant.now().plusSeconds(1),
                false,
                Instant.now().plusSeconds(1)));

        await().atMost(Duration.ofSeconds(10)).until(() -> "EXECUTED"
                .equals(sql.sql("select plan_status from trading_plan where plan_id = ?")
                        .param(plan)
                        .query(String.class)
                        .single()));
        assertThat(ordersOf(plan))
                .extracting(row -> row.get("order_type") + " " + row.get("order_status"))
                .contains("STOP_MARKET FILLED", "MARKET FILLED");
        assertThat(sql.sql("select source from trading_journal where plan_id = ?")
                        .param(plan)
                        .query(String.class)
                        .list())
                .containsExactly("SIMULATED");
    }

    /** BR-68: without a demo account the activation is refused, and no plan and no order is stored. */
    @Test
    void BR68_withoutADemoAccount_theActivationIsRefused_andNothingIsStored() throws Exception {
        UUID trader = trader();
        spotLast("100");

        as(trader, post(PLANS), spot("MARKET", null, true)).andExpect(status().isNotFound());

        assertThat(planCount(trader)).isZero();
        assertThat(orderCount(trader)).isZero();
    }

    /** BR-71: an entry the demo wallet cannot pay is refused, and the activation rolls back with it. */
    @Test
    void BR71_anEntryTheWalletCannotPay_rollsTheActivationBack() throws Exception {
        UUID trader = traderWithAccount();
        sql.sql("""
                        update wallet_balance set free_amount = 50
                         where user_id = ? and wallet_type = 'SPOT'
                           and coin_id = (select coin_id from coin where symbol = 'USDT')""").param(trader).update();
        spotLast("100");

        as(trader, post(PLANS), spot("MARKET", null, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_INSUFFICIENT_BALANCE"));

        assertThat(planCount(trader)).isZero();
        assertThat(orderCount(trader)).isZero();
    }

    /** A draft places nothing. */
    @Test
    void RF33_aDraft_placesNoOrder() throws Exception {
        UUID trader = traderWithAccount();
        spotLast("100");

        as(trader, post(PLANS), spot("MARKET", null, false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        assertThat(orderCount(trader)).isZero();
    }

    /**
     * UC-21 on the demo account: the Spot plan's position is estimated and closed at the market. Its take profit and
     * stop loss are cancelled, the coin it holds is sold, and the journal record closes as a manual close.
     */
    @Test
    void UC21_aSpotPlansPosition_isEstimatedAndClosedOnTheDemoAccount() throws Exception {
        UUID trader = traderWithAccount();
        spotLast("100");
        UUID plan = executedSpotMarket(trader);
        spotLast("104");

        String estimate = body(
                as(trader, get(PLANS + "/" + plan + "/close-estimate"), null).andExpect(status().isOk()));
        assertThat(decimal(estimate, "$.currentPrice")).isEqualByComparingTo("104");
        // 1.998 held (2 less the 0.1 % fee): (104 − 100) × 1.998 = 7.992; fees 0.2 at entry, 104 × 1.998 × 0.001.
        assertThat(decimal(estimate, "$.priceResult")).isEqualByComparingTo("7.992");
        assertThat(decimal(estimate, "$.estimatedExitFee")).isEqualByComparingTo("0.207792");

        String closed = body(as(trader, post(PLANS + "/" + plan + "/close-position"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED")));
        assertThat((String) JsonPath.read(closed, "$.position.status")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(closed, "$.position.closeReason")).isEqualTo("MANUAL");
        await().atMost(Duration.ofSeconds(10)).until(() -> closedNotices(trader) == 1);

        assertThat(ordersOf(plan))
                .extracting(row -> row.get("order_status"))
                .doesNotContain("NEW", "PENDING_NEW", "PARTIALLY_FILLED");
        assertThat(spot(trader, base())).as("all of the coin sold").isZero();
        assertThat(sql.sql("select trade_status from trading_journal where plan_id = ?")
                        .param(plan)
                        .query(String.class)
                        .single())
                .isEqualTo("CLOSED");
    }

    /** D-124 on the demo account: new levels replace the Spot OCO with one at the new prices. */
    @Test
    void D124_newLevels_replaceTheSpotOcoOfThePlan() throws Exception {
        UUID trader = traderWithAccount();
        spotLast("100");
        UUID plan = executedSpotMarket(trader);

        as(trader, patch(PLANS + "/" + plan + "/position-levels"), "{\"stopLoss\": 97, \"takeProfit\": 115}")
                .andExpect(status().isOk());

        List<Map<String, Object>> live = sql.sql("""
                        select order_type, coalesce(limit_price, stop_price) as level from trade_order
                         where plan_id = ? and order_status = 'NEW' order by order_type""").param(plan).query().listOfRows();
        assertThat(live).extracting(row -> row.get("order_type")).containsExactly("LIMIT_MAKER", "STOP_LOSS");
        assertThat((BigDecimal) live.get(0).get("level")).isEqualByComparingTo("115");
        assertThat((BigDecimal) live.get(1).get("level")).isEqualByComparingTo("97");
        assertThat(sql.sql("select stop_loss_price from trading_journal where plan_id = ?")
                        .param(plan)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("97");
    }

    /**
     * A Futures plan's position on the demo account: its detail shows the liquidation price paper trading reads, new
     * levels replace its conditional orders, and a close reduces it at the market.
     */
    @Test
    void UC21_D124_aFuturesPlansPosition_isManagedOnTheDemoAccount() throws Exception {
        UUID trader = traderWithAccount();
        futuresLast("100");
        String created = body(as(trader, post(PLANS), futuresMarket())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED")));
        UUID plan = UUID.fromString(JsonPath.read(created, "$.id"));

        String detail = body(as(trader, get(PLANS + "/" + plan), null).andExpect(status().isOk()));
        assertThat(decimal(detail, "$.position.liquidationPrice")).isPositive();

        as(trader, patch(PLANS + "/" + plan + "/position-levels"), "{\"stopLoss\": 96, \"takeProfit\": 112}")
                .andExpect(status().isOk());
        List<Map<String, Object>> live = sql.sql("""
                        select order_type, stop_price from trade_order
                         where plan_id = ? and order_status = 'NEW' order by order_type""").param(plan).query().listOfRows();
        assertThat(live).extracting(row -> row.get("order_type")).containsExactly("STOP_MARKET", "TAKE_PROFIT_MARKET");
        assertThat((BigDecimal) live.get(0).get("stop_price")).isEqualByComparingTo("96");
        assertThat((BigDecimal) live.get(1).get("stop_price")).isEqualByComparingTo("112");

        String closed = body(
                as(trader, post(PLANS + "/" + plan + "/close-position"), null).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(closed, "$.position.status")).isEqualTo("CLOSED");
        assertThat(sql.sql("select position_status from futures_position where user_id = ?")
                        .param(trader)
                        .query(String.class)
                        .single())
                .isEqualTo("CLOSED");
    }

    /**
     * BR-35 on the demo account: the take profit of the plan's OCO fills on a trade through it. The stop expires and
     * gives the coin back, the journal record closes as a take profit, and MSG23 is sent once.
     */
    @Test
    void BR35_theTakeProfitOfASpotPlan_fillsOnPaper_closesTheRecord_andSendsMsg23() throws Exception {
        UUID trader = traderWithAccount();
        spotLast("100");
        UUID plan = executedSpotMarket(trader);

        paperWorker.onTrade(new MarketTrade(
                MarketType.SPOT,
                pair,
                TICKS.incrementAndGet(),
                new BigDecimal("110.01"),
                BigDecimal.ONE,
                Instant.now().plusSeconds(1),
                false,
                Instant.now().plusSeconds(1)));

        await().atMost(Duration.ofSeconds(10)).until(() -> "CLOSED"
                .equals(sql.sql("select trade_status from trading_journal where plan_id = ?")
                        .param(plan)
                        .query(String.class)
                        .single()));
        assertThat(sql.sql("select close_reason from trading_journal where plan_id = ?")
                        .param(plan)
                        .query(String.class)
                        .single())
                .isEqualTo("TAKE_PROFIT");
        assertThat(locked(trader, base())).as("the stop's lock given back").isZero();
        assertThat(spot(trader, base())).as("all of the coin sold").isZero();
        assertThat(ordersOf(plan))
                .extracting(row -> row.get("order_status"))
                .doesNotContain("NEW", "PENDING_NEW", "PARTIALLY_FILLED");
        await().atMost(Duration.ofSeconds(10)).until(() -> closedNotices(trader) == 1);
    }

    private long closedNotices(UUID trader) {
        return sql.sql("select count(*) from notification where user_id = ? and source_key like 'POSITION_CLOSED:%'")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private UUID executedSpotMarket(UUID trader) throws Exception {
        String created = body(as(trader, post(PLANS), spot("MARKET", null, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED")));
        return UUID.fromString(JsonPath.read(created, "$.id"));
    }

    private String futuresMarket() {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"FUTURES\", \"direction\": \"LONG\","
                + " \"entryType\": \"MARKET\", \"stopLoss\": 95, \"takeProfit\": 110,"
                + " \"leverage\": 5, \"activate\": true}";
    }

    private static BigDecimal decimal(String json, String path) {
        Object value = JsonPath.read(json, path);
        return new BigDecimal(String.valueOf(value));
    }

    // ------------------------------------------------------------------------------------------

    private String spot(String entryType, String entry, boolean activate) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"direction\": \"LONG\", \"entryType\": \""
                + entryType + "\"" + (entry == null ? "" : ", \"entryPrice\": " + entry)
                + ", \"stopLoss\": 95, \"takeProfit\": 110, \"activate\": " + activate + "}";
    }

    private String futuresStop() {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"FUTURES\", \"direction\": \"LONG\","
                + " \"entryType\": \"STOP\", \"entryPrice\": 102, \"stopLoss\": 97, \"takeProfit\": 110,"
                + " \"leverage\": 5, \"activate\": true}";
    }

    private String futuresLimit() {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"FUTURES\", \"direction\": \"LONG\","
                + " \"entryType\": \"LIMIT\", \"entryPrice\": 100, \"stopLoss\": 95, \"takeProfit\": 110,"
                + " \"leverage\": 5, \"activate\": true}";
    }

    private List<Map<String, Object>> ordersOf(UUID plan) {
        return sql.sql("""
                        select order_type, order_status, executed_quantity from trade_order
                         where plan_id = ? order by created_at, order_id""").param(plan).query().listOfRows();
    }

    private long planCount(UUID trader) {
        return sql.sql("select count(*) from trading_plan where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private long orderCount(UUID trader) {
        return sql.sql("select count(*) from trade_order where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private BigDecimal spot(UUID trader, String coin) {
        return balance(trader, coin, "b.free_amount + b.locked_amount");
    }

    private BigDecimal locked(UUID trader, String coin) {
        return balance(trader, coin, "b.locked_amount");
    }

    private BigDecimal balance(UUID trader, String coin, String column) {
        return sql.sql("select coalesce(sum(" + column + "), 0) from wallet_balance b join coin c on c.coin_id ="
                        + " b.coin_id where b.user_id = ? and b.wallet_type = 'SPOT' and c.symbol = ?")
                .params(trader, coin)
                .query(BigDecimal.class)
                .single();
    }

    private String base() {
        return symbol.replace("USDT", "");
    }

    private void spotLast(String last) {
        BigDecimal value = new BigDecimal(last);
        prices.record(
                MarketType.SPOT,
                new TickerMessage(
                        symbol,
                        value,
                        value,
                        value,
                        value,
                        value,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        Instant.now().plusMillis(TICKS.incrementAndGet())));
        prices.flush();
    }

    private void futuresLast(String last) {
        BigDecimal value = new BigDecimal(last);
        prices.record(
                MarketType.FUTURES,
                new TickerMessage(
                        symbol,
                        value,
                        value,
                        value,
                        value,
                        value,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        Instant.now().plusMillis(TICKS.incrementAndGet())));
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

    private UUID traderWithAccount() throws Exception {
        UUID id = trader();
        as(id, post("/api/v1/paper/account"), null).andExpect(status().isCreated());
        return id;
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@rf33.invalid", now(), now()).update();
        sql.sql("""
                        insert into user_profile (user_id, display_name, default_capital, risk_profile, created_at,
                                                  updated_at)
                        values (?, 'Trader', 1000, 'BALANCED', ?, ?)""").params(id, now(), now()).update();
        traders.add(id);
        return id;
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
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
