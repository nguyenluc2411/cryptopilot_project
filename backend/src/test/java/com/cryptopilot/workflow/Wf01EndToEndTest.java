package com.cryptopilot.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.market.MarkPrice;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.dto.request.LeverageBracketsRequest;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.paper.job.PaperMatchingWorker;
import com.cryptopilot.schema.DemoWf01Data;
import com.cryptopilot.support.ClosedKlines;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.OwnDatabaseConfig;
import com.cryptopilot.support.StreamedPrices;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.job.MatchingWorker;
import com.cryptopilot.trading.job.MinuteKlineFeed;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Workflow WF-01 end to end (E2E-WF01) on the one engine of D-117 (RF-33): a plan is carried out by paper orders on the
 * Trader's demo account, driven through the public HTTP API as the web app calls it, by the demo accounts of DEMO-01
 * signed in with their passwords.
 *
 * <p>BM04 DS-01a is the main scenario: activate → the MARKET entry fills → plan EXECUTED, the entry order FILLED, the
 * demo wallet changed, the journal record SIMULATED. The Spot fee is taken in the coin the fill brings in (TR-13, as
 * the exchange does), so the wallet holds 4,000 USDT and 2.998 ETH after the buy of 2 ETH at 3,000 (BM04 counts the
 * same 6 USDT of fee in USDT: 3,994 USDT and 3 ETH). The other tests keep the Design Brief scenarios on the demo
 * account: a Futures plan closed by hand, a LIMIT plan resting in the paper book, the refusals on the plan itself, a
 * moved MARKET entry (BR-69), the plan limit of FREE (BR-62), an invalidated LIMIT and new levels of an open position.
 *
 * <p>The backend runs on the {@code demo} profile, on a database of its own, so Flyway applies the demo accounts:
 * demo.free (FREE, CONSERVATIVE, 10,000 at 1 %), demo.pro (PRO, BALANCED, 10,000 at 1 %) and the administrator. The
 * administrator sets the pairs up as the loader does, through the admin API; {@code SLIPPAGE_BPS} is changed through
 * the same API and prices the plan panel only (decision A of RF-33): the order fills at the paper price like any other.
 * Each test opens the demo accounts of both Traders (10,000 USDT, 0.1 BTC, 1 ETH, 10 BNB on Spot; 5,000 USDT on
 * Futures) and removes them afterwards.
 *
 * <p>No network. What the exchange would send is handed in by the test: the pairs (Spot tick 0.01, step 0.00001;
 * Futures tick 0.1, step 0.001, minimum notional 100), last and mark prices into the price cache, trades and mark
 * prices to the paper matching consumer, and closed 1-minute candles to the plan feed that invalidates waiting plans.
 * The clock is the test's: every test starts at 07:58:30 on 2026-11-02. Fees are the seeded ones, charged on the demo
 * account and estimated by the plan panel alike: Futures maker 0.02 %, taker 0.05 % (TR-44); Spot 0.1 %.
 *
 * <p>NFR N3 of the Design Brief: a fill or an exit decided by the market is visible through the API at most 3 s after
 * the price reached the engine; each measurement is logged.
 *
 * <p>Rule: WF-01; UC-16 to UC-21; BR-23 to BR-39, BR-62, BR-68, BR-69, BR-71; NSF-07; MSG16, MSG18, MSG22, MSG23,
 * MSG27, MSG51, MSG52, MSG54; D-117, D-124; RF-33; BM04 DS-01a.
 */
@SpringBootTest(
        properties = {
            OwnDatabaseConfig.PROPERTY + "=" + Wf01EndToEndTest.DATABASE,
            "cryptopilot.trading.matching.partitions=1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import({TestcontainersConfig.class, OwnDatabaseConfig.class, Wf01EndToEndTest.ClockConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Wf01EndToEndTest {

    static final String DATABASE = "cryptopilot_e2e_wf01";

    private static final Logger log = LoggerFactory.getLogger(Wf01EndToEndTest.class);

    private static final String ADMIN_EMAIL = "admin@cryptopilot.invalid";
    private static final String PRO_EMAIL = "demo.pro@cryptopilot.invalid";
    private static final String FREE_EMAIL = "demo.free@cryptopilot.invalid";
    private static final String ADMIN_PASSWORD = "admin-password-for-the-wf01-test";
    private static final String DEMO_PASSWORD = "demo-password-for-the-wf01-test";
    private static final UUID DEMO_FREE = UUID.fromString("019b76da-a800-7d04-8000-000000000001");
    private static final UUID DEMO_PRO = UUID.fromString("019b76da-a800-7d04-8000-000000000002");
    private static final List<UUID> TRADERS = List.of(DEMO_FREE, DEMO_PRO);

    private static final String PLANS = "/api/v1/plans";
    private static final String BTC = "BTCUSDT";
    private static final String ETH = "ETHUSDT";
    private static final AtomicLong TRADE_IDS = new AtomicLong();

    /** The paper rows of a Trader, children first. */
    private static final List<String> PAPER_TABLES = List.of(
            "futures_margin_adjustment",
            "order_fill",
            "futures_position",
            "futures_symbol_config",
            "futures_account_config",
            "trade_order",
            "order_list",
            "wallet_ledger_entry",
            "wallet_balance",
            "equity_snapshot");

    /** The minute the clock starts in; a test starts 30 s into it. */
    private static final Instant MINUTE_0 = Instant.parse("2026-11-02T07:58:00Z");

    private static final Instant START = MINUTE_0.plusSeconds(30);
    private static final Duration N3 = Duration.ofSeconds(3);
    private static final String ENTRY_FILLED = "Entry filled";
    private static final String POSITION_CLOSED = "Position closed";

    @DynamicPropertySource
    static void passwords(DynamicPropertyRegistry registry) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String admin = encoder.encode(ADMIN_PASSWORD);
        String demo = encoder.encode(DEMO_PASSWORD);
        registry.add("spring.flyway.placeholders.admin_password_hash", () -> admin);
        registry.add("spring.flyway.placeholders.demo_password_hash", () -> demo);
    }

    @MockitoSpyBean
    private MarketApi market;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private PriceCacheService prices;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MinuteKlineFeed feed;

    @Autowired
    private MatchingWorker worker;

    @Autowired
    private PaperMatchingWorker paper;

    @Autowired
    private MutableTestClock clock;

    private final DemoWf01Data data = DemoWf01Data.read();
    private final Map<String, UUID> pairs = new LinkedHashMap<>();

    private String admin;
    private String pro;
    private String free;
    private boolean slippageChanged;

    /**
     * What the symbol synchronisation and the loader leave behind on a fresh staging database: both pairs listed by the
     * exchange, then switched on with their brackets by the administrator through the admin API.
     */
    @BeforeAll
    void pairsAsTheLoaderSetsThemUp() throws Exception {
        assertThat(sql.sql("select current_database()").query(String.class).single())
                .as("the demo set runs on its own database, never the one the suite shares")
                .isEqualTo(DATABASE);
        clock.set(START);
        MarketTestData exchange = new MarketTestData(sql, START);
        int order = 1;
        for (String symbol : data.symbols()) {
            pairs.put(symbol, exchange.pair(symbol, false, false, "TRADING", "TRADING", order++));
        }
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.00001, spot_min_notional = 5,
                               futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 100""").update();
        String token = signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
        JsonMapper json = JsonMapper.builder().build();
        order = 1;
        for (String symbol : data.symbols()) {
            UUID pair = pairs.get(symbol);
            call(
                            token,
                            put("/api/v1/admin/pairs/" + pair + "/brackets"),
                            json.writeValueAsString(new LeverageBracketsRequest(data.brackets(symbol))))
                    .andExpect(status().isOk());
            call(
                            token,
                            put("/api/v1/admin/pairs/" + pair),
                            "{\"status\": \"ACTIVE\", \"spotEnabled\": true, \"futuresEnabled\": true,"
                                    + " \"maxLeverage\": " + data.maxLeverage(symbol) + ", \"displayOrder\": "
                                    + order++ + "}")
                    .andExpect(status().isOk());
        }
    }

    @BeforeEach
    void openTheDemoAccounts() throws Exception {
        feed.stop();
        worker.stop();
        clock.set(START);
        removeCachedPrices();
        doReturn(MinuteKlineBatch.of(List.of())).when(market).closedMinuteKlines(any(), any(), any());
        doReturn(MinuteKlineBatch.of(List.of())).when(market).closedMarkPriceKlines(any(), any());
        feed.start();
        admin = signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
        pro = signIn(PRO_EMAIL, DEMO_PASSWORD);
        free = signIn(FREE_EMAIL, DEMO_PASSWORD);
        for (String token : List.of(pro, free)) {
            call(token, post("/api/v1/paper/account"), null).andExpect(status().isCreated());
        }
        slippageChanged = false;
    }

    /**
     * Ends what still works through the API first, so the paper book in memory lets go of the orders, then removes the
     * plans, the journal and the demo accounts.
     */
    @AfterEach
    void removeWhatTheTestWrote() throws Exception {
        endWhatStillWorks();
        feed.stop();
        worker.stop();
        if (slippageChanged) {
            changeSetting("SLIPPAGE_BPS", "0");
        }
        InAppNotifications.awaitDeliveries(sql);
        InAppNotifications.remove(sql, TRADERS);
        for (UUID trader : TRADERS) {
            sql.sql("""
                            delete from trading_journal_level_change where journal_id in
                                   (select journal_id from trading_journal where user_id = ?)""").param(trader).update();
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(trader)
                    .update();
            for (String table : PAPER_TABLES) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(trader)
                        .update();
            }
            sql.sql("delete from trading_plan where user_id = ?").param(trader).update();
        }
        sql.sql("update user_profile set default_capital = 10000 where user_id = ?")
                .param(DEMO_PRO)
                .update();
        sql.sql("delete from matching_watermark").update();
        sql.sql("delete from order_matching_watermark").update();
        removeCachedPrices();
    }

    private void endWhatStillWorks() throws Exception {
        for (String token : List.of(pro, free)) {
            List<Map<String, Object>> items = JsonPath.read(
                    json(call(token, get(PLANS).param("pageSize", "100"), null).andExpect(status().isOk())),
                    "$.items[*]");
            for (Map<String, Object> item : items) {
                String id = String.valueOf(item.get("id"));
                if ("ACTIVE".equals(item.get("status"))) {
                    call(token, post(PLANS + "/" + id + "/cancel"), null);
                } else if ("EXECUTED".equals(item.get("status"))
                        && "OPEN".equals(JsonPath.read(plan(token, id), "$.position.status"))) {
                    call(token, post(PLANS + "/" + id + "/close-position"), null);
                }
            }
        }
    }

    /**
     * The price cache is shared with the whole suite, and it keeps an entry only while no newer one arrives
     * ({@code WRITE_IF_NEWER}). This class streams prices at its own clock, which can be ahead of the wall clock, so
     * an entry it leaves would hide the prices of the next class, and one left by another class would hide this
     * test's. Cleared before and after every test.
     */
    private void removeCachedPrices() {
        for (String symbol : pairs.keySet()) {
            redis.delete(List.of("px:SPOT:" + symbol, "px:FUTURES:" + symbol));
        }
    }

    // ------------------------------------------------------------------------------------------
    // BM04 DS-01a: a Spot MARKET plan filled on the demo wallet (main scenario)
    // ------------------------------------------------------------------------------------------

    /**
     * BM04 DS-01a. demo.pro, capital 20,000 at 1 %, demo wallet opened (BR-68), ETHUSDT at 3,000: entry 3,000, SL
     * 2,900, TP 3,300. Created and activated, the MARKET entry fills at once: quantity 2 ETH, cost 6,000, max loss 200,
     * R/R 3.0; the plan EXECUTED, the order FILLED, the wallet 4,000 USDT and 2.998 ETH (the 0.1 % fee of 0.002 ETH,
     * 6 USDT at the price, taken from the coin bought), the take profit and stop loss waiting as an OCO sale of the
     * 1.998 ETH; the journal record SIMULATED; MSG22 sent.
     */
    @Test
    void DS01a_spotMarketPlan_fillsOnTheDemoWallet() throws Exception {
        sql.sql("update user_profile set default_capital = 20000 where user_id = ?")
                .param(DEMO_PRO)
                .update();
        spotLast(ETH, "3000");
        String body = spotMarket(ETH, "2900", "3300", true);

        String panel = json(call(pro, post(PLANS + "/calculate"), body).andExpect(status().isOk()));
        // 20,000 × 1 % = 200 at risk over a stop 100 below 3,000: 2 ETH, 6,000.
        assertDecimal(panel, "$.snapshot.positionQuantity", "2");
        assertDecimal(panel, "$.snapshot.notionalValue", "6000");
        assertDecimal(panel, "$.snapshot.riskAmount", "200");
        // (3,300 − 3,000) / (3,000 − 2,900) = 3.
        assertDecimal(panel, "$.snapshot.riskRewardRatio", "3");
        assertThat((Boolean) JsonPath.read(panel, "$.blocksActivation")).isFalse();

        String created = json(call(pro, post(PLANS), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.position.status").value("OPEN")));
        String id = JsonPath.read(created, "$.id");
        assertThat(statuses(created)).containsExactly("DRAFT", "ACTIVE", "EXECUTED");
        assertDecimal(created, "$.position.fillPrice", "3000");

        List<Map<String, Object>> orders = ordersOf(id);
        assertThat(orders)
                .extracting(row -> row.get("order_type") + " " + row.get("order_status"))
                .containsExactly("MARKET FILLED", "LIMIT_MAKER NEW", "STOP_LOSS NEW");
        assertThat((BigDecimal) orders.getFirst().get("executed_quantity")).isEqualByComparingTo("2");

        String wallet = spotWallet(pro);
        assertDecimal(wallet, "$.balances[?(@.asset == 'USDT')].total", "4000");
        assertDecimal(wallet, "$.balances[?(@.asset == 'ETH')].total", "2.998");
        assertDecimal(wallet, "$.balances[?(@.asset == 'ETH')].locked", "1.998");

        String open = journal(pro, "OPEN");
        assertThat((String) JsonPath.read(open, "$.items[0].planId")).isEqualTo(id);
        assertThat((String) JsonPath.read(open, "$.items[0].source")).isEqualTo("SIMULATED");
        assertDecimal(open, "$.items[0].entryPrice", "3000");
        assertThat(notification(pro, id, ENTRY_FILLED)).isEqualTo("ETHUSDT LONG entry filled at 3000.");
    }

    /**
     * DS-01a, the exit: a trade through 3,300 fills the take profit of the OCO as a maker at its price. The stop loss
     * expires, the 1.998 ETH are sold, the journal record closes as TAKE_PROFIT and MSG23 names the close.
     */
    @Test
    void DS01a2_spotPlan_closedByTakeProfit_onTheDemoWallet() throws Exception {
        sql.sql("update user_profile set default_capital = 20000 where user_id = ?")
                .param(DEMO_PRO)
                .update();
        spotLast(ETH, "3000");
        String id = id(call(pro, post(PLANS), spotMarket(ETH, "2900", "3300", true))
                .andExpect(jsonPath("$.status").value("EXECUTED")));

        clock.set(START.plusSeconds(20));
        long handed = trade(MarketType.SPOT, ETH, "3300.01");
        String closed = awaitVisible("the take profit exit", handed, () -> plan(pro, id), "CLOSED");

        assertThat((String) JsonPath.read(closed, "$.position.closeReason")).isEqualTo("TAKE_PROFIT");
        assertDecimal(closed, "$.position.exitPrice", "3300");
        String wallet = spotWallet(pro);
        // 1 ETH of the grant left; 4,000 + 3,300 × 1.998 × (1 − 0.001) = 4,000 + 6,586.8066.
        assertDecimal(wallet, "$.balances[?(@.asset == 'ETH')].total", "1");
        assertDecimal(wallet, "$.balances[?(@.asset == 'USDT')].total", "10586.8066");
        assertThat(ordersOf(id))
                .extracting(row -> row.get("order_status"))
                .doesNotContain("NEW", "PENDING_NEW", "PARTIALLY_FILLED");
        String entry = journal(pro, "CLOSED");
        assertThat((String) JsonPath.read(entry, "$.items[0].planId")).isEqualTo(id);
        assertThat((String) JsonPath.read(entry, "$.items[0].closeReason")).isEqualTo("TAKE_PROFIT");
        assertThat(notification(pro, id, POSITION_CLOSED))
                .startsWith("ETHUSDT position closed by take profit at 3300.");
    }

    // ------------------------------------------------------------------------------------------
    // A Futures MARKET plan closed by hand
    // ------------------------------------------------------------------------------------------

    /**
     * demo.pro plans BTCUSDT Futures MARKET LONG, SL 99,500, TP 101,500, leverage 5, risk 1 % (100) with the last price
     * at 100,000 and {@code SLIPPAGE_BPS} 10. The panel prices the entry 10 bps higher; saved and activated, the plan's
     * MARKET order fills on the demo account at the paper price, 100,000, as a taker at the 0.05 % its panel estimated
     * (TR-44). At 08:05 the Trader estimates and
     * closes the position at 100,600; the plan stays EXECUTED and the journal entry is CLOSED as MANUAL.
     */
    @Test
    void DS01a3_futuresMarketPlan_closedByHand() throws Exception {
        slippage("10");
        futuresLast("100000");
        String body = futures("MARKET", null, "99500", "101500", 5, true);

        String panel = json(call(pro, post(PLANS + "/calculate"), body).andExpect(status().isOk()));
        // 100,000 × 1.001 = 100,100.0; 100 / (100,100 − 99,500) = 0.1666… → 0.166 on the step 0.001.
        assertDecimal(panel, "$.expectedFillPrice", "100100");
        assertDecimal(panel, "$.snapshot.positionQuantity", "0.166");
        assertThat((Boolean) JsonPath.read(panel, "$.blocksActivation")).isFalse();

        String created = json(call(pro, post(PLANS), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.position.status").value("OPEN")));
        String id = JsonPath.read(created, "$.id");
        assertDecimal(created, "$.position.fillPrice", "100000");
        assertDecimal(created, "$.position.quantity", "0.166");
        // Taker: 0.166 × 100,000 × 0.0005 = 8.3.
        assertDecimal(created, "$.position.entryFee", "8.3");
        assertThat(statuses(created)).containsExactly("DRAFT", "ACTIVE", "EXECUTED");
        assertThat(ordersOf(id))
                .extracting(row -> row.get("order_type") + " " + row.get("order_status"))
                .containsExactlyInAnyOrder("MARKET FILLED", "TAKE_PROFIT_MARKET NEW", "STOP_MARKET NEW");

        clock.set(Instant.parse("2026-11-02T08:05:00Z"));
        futuresLast("100600");
        String estimate =
                json(call(pro, get(PLANS + "/" + id + "/close-estimate"), null).andExpect(status().isOk()));
        // (100,600 − 100,000) × 0.166 = 99.6; exit fee 100,600 × 0.166 × 0.0005 = 8.3498.
        assertDecimal(estimate, "$.priceResult", "99.6");
        assertDecimal(estimate, "$.estimatedExitFee", "8.3498");

        String closed = json(call(pro, post(PLANS + "/" + id + "/close-position"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.position.status").value("CLOSED"))
                .andExpect(jsonPath("$.position.closeReason").value("MANUAL")));
        assertDecimal(closed, "$.position.exitPrice", "100600");

        String entry = journal(pro, "CLOSED");
        assertThat((String) JsonPath.read(entry, "$.items[0].planId")).isEqualTo(id);
        assertThat((String) JsonPath.read(entry, "$.items[0].closeReason")).isEqualTo("MANUAL");
        assertDecimal(entry, "$.items[0].entryPrice", "100000");
        assertDecimal(entry, "$.items[0].exitPrice", "100600");
        assertDecimal(entry, "$.items[0].quantity", "0.166");
        // 99.6 − 8.3 − 8.3498 = 82.9502.
        assertDecimal(entry, "$.items[0].netPnl", "82.9502");
        assertThat(notification(pro, id, POSITION_CLOSED))
                .isEqualTo("BTCUSDT position closed by manual close at 100600. P/L: 82.9502.");
        assertThat(JsonPath.<List<Object>>read(futuresPositions(pro), "$[?(@.positionAmt != 0)]"))
                .as("nothing left open on the demo account")
                .isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // DS-01b: a LIMIT plan resting in the paper book
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01b. demo.pro plans BTCUSDT Futures LIMIT LONG at 99,000 (SL 98,010, TP 101,970, leverage 5) with the last
     * price at 100,000. An invalidation price below the price is MSG16 on its field; at 102,000 the plan is ACTIVE with
     * the default expiry of 7 days and its LIMIT order rests in the paper book. A trade at 98,990 fills it at 99,000 as
     * a maker, and the plan is EXECUTED.
     */
    @Test
    void DS01b_limitPlan_restsInThePaperBook_untilATradeReachesIt() throws Exception {
        futuresLast("100000");
        call(
                        pro,
                        post(PLANS),
                        withField(futures("LIMIT", "99000", "98010", "101970", 5, true), "invalidationPrice", "99500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.invalidationPrice").value("MSG16"));
        assertThat(planCount(pro)).isZero();

        String created = json(call(
                        pro,
                        post(PLANS),
                        withField(futures("LIMIT", "99000", "98010", "101970", 5, true), "invalidationPrice", "102000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.position").doesNotExist()));
        String id = JsonPath.read(created, "$.id");
        assertThat(statuses(created)).containsExactly("DRAFT", "ACTIVE");
        assertThat(Instant.parse(JsonPath.read(created, "$.expiresAt"))).isEqualTo(START.plus(Duration.ofDays(7)));
        // 100 / (99,000 − 98,010) = 0.10101… → 0.101.
        assertDecimal(created, "$.snapshot.positionQuantity", "0.101");
        assertThat(ordersOf(id))
                .extracting(row -> row.get("order_type") + " " + row.get("order_status"))
                .contains("LIMIT NEW");

        clock.set(START.plusSeconds(20));
        long handed = trade(MarketType.FUTURES, BTC, "98990");
        String filled = awaitVisible("the LIMIT fill", handed, () -> plan(pro, id), "$.status", "EXECUTED");

        assertThat(statuses(filled)).containsExactly("DRAFT", "ACTIVE", "EXECUTED");
        assertThat((String) JsonPath.read(filled, "$.position.status")).isEqualTo("OPEN");
        assertDecimal(filled, "$.position.fillPrice", "99000");
        // Maker: 99,000 × 0.101 × 0.0002 = 1.9998.
        assertDecimal(filled, "$.position.entryFee", "1.9998");
        String open = journal(pro, "OPEN");
        assertThat((String) JsonPath.read(open, "$.items[0].planId")).isEqualTo(id);
        assertThat((String) JsonPath.read(open, "$.items[0].source")).isEqualTo("SIMULATED");
    }

    // ------------------------------------------------------------------------------------------
    // DS-01c, DS-01d: exceptions on the plan itself
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01c. demo.pro plans BTCUSDT Futures LIMIT LONG at 99,000 with SL 94,050 and leverage 50 (tier 1 of the demo
     * brackets: up to 125x, MMR 0.004). The liquidation price lies above the stop loss: BLOCKING, plus a WARNING for
     * leverage above BALANCED's 5x. The draft is saved with them; activation is MSG18, it stays DRAFT and no order is
     * placed.
     */
    @Test
    void DS01c_stopBeyondLiquidation_isBlocked() throws Exception {
        futuresLast("100000");
        String body = futures("LIMIT", "99000", "94050", "104000", 50, false);

        String panel = json(call(pro, post(PLANS + "/calculate"), body).andExpect(status().isOk()));
        // 100 / 4,950 = 0.0202… → 0.020; notional 1,980, margin at 50x 39.6.
        assertDecimal(panel, "$.snapshot.positionQuantity", "0.02");
        // (1,980 − 39.6) / (0.020 × 0.996) = 97,409.638… → 97,409.7, above the stop loss 94,050.
        assertDecimal(panel, "$.snapshot.estimatedLiquidationPrice", "97409.7");
        assertThat((Boolean) JsonPath.read(panel, "$.blocksActivation")).isTrue();
        assertThat(severities(panel))
                .containsEntry("SL_BEYOND_LIQUIDATION", "BLOCKING")
                .containsEntry("HIGH_LEVERAGE", "WARNING");

        String draft = json(call(pro, post(PLANS), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.blocksActivation").value(true)));
        String id = JsonPath.read(draft, "$.id");

        call(pro, post(PLANS + "/" + id + "/activate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRADING_BLOCKING_WARNING"))
                .andExpect(jsonPath("$.messageCode").value("MSG18"));
        String after = plan(pro, id);
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("DRAFT");
        assertThat(statuses(after)).containsExactly("DRAFT");
        assertThat(ordersOf(id)).isEmpty();
    }

    /**
     * DS-01d. demo.free asks for a Futures plan: since V61 every plan includes the Futures analysis (the registered
     * topic gives every Trader Spot and Futures plans), so the panel answers and the draft is saved.
     */
    @Test
    void DS01d_freeAccount_futuresPlan_isCalculatedAndSaved() throws Exception {
        futuresLast("100000");
        String body = futures("LIMIT", "99000", "98010", "101970", 5, false);

        call(free, post(PLANS + "/calculate"), body).andExpect(status().isOk());
        call(free, post(PLANS), body).andExpect(status().isCreated());
        assertThat(planCount(free)).isOne();
    }

    // ------------------------------------------------------------------------------------------
    // DS-01e: a MARKET entry that moved since the draft (BR-69)
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01e. Three demo.pro drafts of BTCUSDT Futures MARKET LONG, SL 99,000, TP 102,000, saved with the last price at
     * 100,000 (1R = 1,000). At 100,500 (0.5R) activation is MSG51 with the recalculated plan until confirmed, then
     * fills at 100,500 on the demo account; at 101,000 (1R, beyond 0.75R) it is MSG52 even confirmed and stays a draft
     * with no order; at 100,200 (0.2R, under 0.25R) it fills directly.
     */
    @Test
    void DS01e_movedMarketEntry_confirmOrRefuse() throws Exception {
        futuresLast("100000");
        String body = futures("MARKET", null, "99000", "102000", 5, false);
        List<String> drafts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String draft = json(call(pro, post(PLANS), body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT")));
            assertDecimal(draft, "$.plannedEntryPrice", "100000");
            // 100 / 1,000 = 0.1.
            assertDecimal(draft, "$.snapshot.positionQuantity", "0.1");
            drafts.add(JsonPath.read(draft, "$.id"));
        }

        clock.set(START.plusSeconds(10));
        futuresLast("100500");
        String required = json(call(pro, post(PLANS + "/" + drafts.get(0) + "/activate"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENTRY_DEVIATION_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.messageCode").value("MSG51")));
        // 500 / 1,000 = 0.5R; 100 / 1,500 = 0.0666… → 0.066; (102,000 − 100,500) / 1,500 = 1.
        assertDecimal(required, "$.deviationR", "0.5");
        assertDecimal(required, "$.maxDeviationR", "0.75");
        assertDecimal(required, "$.positionQuantity", "0.066");
        assertDecimal(required, "$.riskRewardRatio", "1");
        assertThat((String) JsonPath.read(plan(pro, drafts.get(0)), "$.status")).isEqualTo("DRAFT");
        assertThat(ordersOf(drafts.get(0))).isEmpty();
        String confirmed =
                json(call(pro, post(PLANS + "/" + drafts.get(0) + "/activate").param("confirmDeviation", "true"), null)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("EXECUTED")));
        assertDecimal(confirmed, "$.position.fillPrice", "100500");
        assertDecimal(confirmed, "$.position.quantity", "0.066");

        clock.set(START.plusSeconds(20));
        futuresLast("101000");
        String refused =
                json(call(pro, post(PLANS + "/" + drafts.get(1) + "/activate").param("confirmDeviation", "true"), null)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("ENTRY_DEVIATION_REFUSED"))
                        .andExpect(jsonPath("$.messageCode").value("MSG52")));
        // 1,000 / 1,000 = 1R against the limit 0.75R.
        assertDecimal(refused, "$.messageArgs[0]", "1");
        assertDecimal(refused, "$.messageArgs[1]", "0.75");
        assertThat((String) JsonPath.read(plan(pro, drafts.get(1)), "$.status")).isEqualTo("DRAFT");
        assertThat(ordersOf(drafts.get(1))).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // DS-01f, DS-01g: the plan limit of FREE (BR-62) and an invalidated LIMIT (BR-68)
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01f. demo.free holds three ACTIVE Spot plans, each with its limit buy resting on the demo account: two of the
     * loader (BTCUSDT and ETHUSDT, far below the price) and a BTCUSDT LIMIT LONG at 99,000 (SL 98,010, TP 101,970,
     * risk 0.5 %), so a fourth is MSG27. The Spot candle of 07:59 rises from 100,500 to 102,100 without coming down to
     * 99,000: the take profit is reached first, the plan is INVALIDATED with that reason, its orders are cancelled and
     * the USDT they locked is free again, MSG54 tells the Trader, and its slot is free for the fourth plan.
     */
    @Test
    void DS01f_limitInvalidated_whenTakeProfitReachedFirst() throws Exception {
        spotLast(BTC, "100000");
        spotLast(ETH, "4000");
        loaderPlansOfDemoFree(2);
        BigDecimal lockedBefore = usdtLocked(free);
        String limit = id(call(free, post(PLANS), spot(BTC, "99000", "98010", "101970", "0.5", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        assertThat(usdtLocked(free)).as("the limit buy locks its cost").isGreaterThan(lockedBefore);
        String fourth = loaderPlan(BTC, false);
        call(free, post(PLANS + "/" + fourth + "/activate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG27"));

        feedClosed(MarketType.SPOT, BTC, 1, "100500", "100400", "102100");
        String invalidated = awaitPlan(free, limit, "INVALIDATED");

        assertThat((String) JsonPath.read(invalidated, "$.invalidationReason")).isEqualTo("TAKE_PROFIT_REACHED");
        assertThat(statuses(invalidated)).containsExactly("DRAFT", "ACTIVE", "INVALIDATED");
        assertThat(ordersOf(limit))
                .extracting(row -> row.get("order_status"))
                .doesNotContain("NEW", "PENDING_NEW", "PARTIALLY_FILLED");
        assertThat(usdtLocked(free)).isEqualByComparingTo(lockedBefore);
        assertThat(notification(free, limit, "Plan invalidated"))
                .isEqualTo("BTCUSDT LONG plan was invalidated before its entry was filled: the take profit was reached"
                        + " first.");
        call(free, post(PLANS + "/" + fourth + "/activate"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /**
     * DS-01g. demo.free holds the three ACTIVE plans of the loader. A fourth by Save and Activate is MSG27 with the
     * limit 3 and the tier FREE, and nothing is saved or placed; a saved draft stays DRAFT when activated. demo.pro
     * activates the same plan.
     */
    @Test
    void DS01g_freeFourthActivePlan_isRefused() throws Exception {
        spotLast(BTC, "100000");
        spotLast(ETH, "4000");
        loaderPlansOfDemoFree(3);
        String fourth = loaderBody(BTC, true);
        long ordersBefore = orderCount(DEMO_FREE);

        call(free, post(PLANS), fourth)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"))
                .andExpect(jsonPath("$.messageCode").value("MSG27"))
                .andExpect(jsonPath("$.messageArgs[0]").value("3"))
                .andExpect(jsonPath("$.messageArgs[2]").value("FREE"));
        assertThat(planCount(free)).isEqualTo(3);
        assertThat(orderCount(DEMO_FREE)).isEqualTo(ordersBefore);

        String draft = loaderPlan(BTC, false);
        call(free, post(PLANS + "/" + draft + "/activate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG27"));
        assertThat((String) JsonPath.read(plan(free, draft), "$.status")).isEqualTo("DRAFT");

        call(pro, post(PLANS), fourth)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ------------------------------------------------------------------------------------------
    // TP-E: new levels of an open position (D-124)
    // ------------------------------------------------------------------------------------------

    /**
     * TP-E. The Futures position of a MARKET plan (filled at 100,000, SL 99,500) has its stop loss raised to 99,800:
     * the stop-market order of the plan is replaced by one at 99,800. A mark price of 99,750 triggers it, the position
     * is sold at the market, and the journal entry closes as STOP_LOSS with the new stop on record.
     */
    @Test
    void TPE_aStopLossChange_replacesTheStopOrder() throws Exception {
        futuresLast("100000");
        String id = id(call(pro, post(PLANS), futures("MARKET", null, "99500", "101500", 5, true))
                .andExpect(jsonPath("$.status").value("EXECUTED")));

        clock.set(START.plusSeconds(30));
        futuresLast("100000");
        String changed = json(call(pro, patch(PLANS + "/" + id + "/position-levels"), "{\"stopLoss\": 99800}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.levelChangeCount").value(1))
                .andExpect(jsonPath("$.position.stopWidened").value(false)));
        assertDecimal(changed, "$.position.stopLoss", "99800");
        assertDecimal(changed, "$.position.originalStopLoss", "99500");
        List<Map<String, Object>> live =
                sql.sql("""
                        select order_type, stop_price from trade_order
                         where plan_id = ? and order_status = 'NEW' order by order_type""").param(UUID.fromString(id)).query().listOfRows();
        assertThat(live).extracting(row -> row.get("order_type")).containsExactly("STOP_MARKET", "TAKE_PROFIT_MARKET");
        assertThat((BigDecimal) live.getFirst().get("stop_price")).isEqualByComparingTo("99800");

        clock.set(START.plusSeconds(40));
        futuresLast("99750");
        long handed = mark(BTC, "99750");
        String closed = awaitVisible("the exit at the new stop", handed, () -> plan(pro, id), "CLOSED");

        assertThat((String) JsonPath.read(closed, "$.position.closeReason")).isEqualTo("STOP_LOSS");
        assertDecimal(closed, "$.position.stopLoss", "99800");
        String entry = journal(pro, "CLOSED");
        assertThat((String) JsonPath.read(entry, "$.items[0].planId")).isEqualTo(id);
        assertThat((String) JsonPath.read(entry, "$.items[0].closeReason"))
                .as("the MARKET order the stop placed closes as the stop (F-19)")
                .isEqualTo("STOP_LOSS");
        assertThat(notification(pro, id, POSITION_CLOSED)).startsWith("BTCUSDT position closed by stop loss at ");
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Hands a market trade at the clock's instant to the paper matching consumer and answers when, for N3. */
    private long trade(MarketType marketType, String symbol, String price) {
        long handed = System.nanoTime();
        paper.onTrade(new MarketTrade(
                marketType,
                pairs.get(symbol),
                TRADE_IDS.incrementAndGet(),
                new BigDecimal(price),
                BigDecimal.ONE,
                clock.instant(),
                false,
                clock.instant()));
        return handed;
    }

    /** The Futures mark price at the clock's instant, into the cache and to the paper consumer; answers when, for N3. */
    private long mark(String symbol, String price) {
        new StreamedPrices(prices).mark(symbol, new BigDecimal(price), clock.instant());
        long handed = System.nanoTime();
        paper.onMarkPrice(new MarkPrice(pairs.get(symbol), new BigDecimal(price), clock.instant()));
        return handed;
    }

    /**
     * Hands the closed candle of minute {@code n} of the pair to the plan feed, which invalidates waiting plans, after
     * the clock moved to its end.
     */
    private void feedClosed(MarketType marketType, String symbol, int n, String open, String low, String high) {
        if (clock.instant().isBefore(minute(n + 1))) {
            clock.set(minute(n + 1));
        }
        feed.onMinuteKline(ClosedKlines.closed(marketType, pairs.get(symbol), minute(n), open, low, high));
    }

    private String awaitPlan(String token, String id, String expected) {
        AtomicReference<String> seen = new AtomicReference<>();
        await("plan " + id + " " + expected)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    seen.set(plan(token, id));
                    return expected.equals(JsonPath.read(seen.get(), "$.status"));
                });
        return seen.get();
    }

    /**
     * Polls {@code read} until {@code path} of its answer is {@code expected}, asserts that it took at most 3 s from
     * {@code handed} (NFR N3) and logs the time it took.
     */
    private String awaitVisible(String what, long handed, Callable<String> read, String path, String expected) {
        AtomicReference<String> seen = new AtomicReference<>();
        await(what)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    String answer = read.call();
                    seen.set(answer);
                    return expected.equals(JsonPath.read(answer, path));
                });
        Duration took = Duration.ofNanos(System.nanoTime() - handed);
        log.info("N3 {}: visible through the API {} ms after the price reached the engine", what, took.toMillis());
        assertThat(took).as("N3: " + what).isLessThanOrEqualTo(N3);
        return seen.get();
    }

    /** {@link #awaitVisible(String, long, Callable, String, String)} on the position status of a plan's detail. */
    private String awaitVisible(String what, long handed, Callable<String> plan, String positionStatus) {
        return awaitVisible(what, handed, plan, "$.position.status", positionStatus);
    }

    private String plan(String token, String id) throws Exception {
        return json(call(token, get(PLANS + "/" + id), null).andExpect(status().isOk()));
    }

    private long planCount(String token) throws Exception {
        return ((Number) JsonPath.read(json(call(token, get(PLANS), null).andExpect(status().isOk())), "$.total"))
                .longValue();
    }

    private String journal(String token, String status) throws Exception {
        return json(call(token, get("/api/v1/journal").param("status", status), null)
                .andExpect(status().isOk()));
    }

    private String spotWallet(String token) throws Exception {
        return json(call(token, get("/api/v1/paper/wallets/SPOT"), null).andExpect(status().isOk()));
    }

    private BigDecimal usdtLocked(String token) throws Exception {
        List<Object> locked = JsonPath.read(spotWallet(token), "$.balances[?(@.asset == 'USDT')].locked");
        return new BigDecimal(String.valueOf(locked.getFirst()));
    }

    private String futuresPositions(String token) throws Exception {
        return json(call(token, get("/api/v1/paper/futures/positionRisk").param("symbol", BTC), null)
                .andExpect(status().isOk()));
    }

    /** The plan's paper orders, oldest first. */
    private List<Map<String, Object>> ordersOf(String planId) {
        return sql.sql("""
                        select order_type, order_status, executed_quantity from trade_order
                         where plan_id = ? order by created_at, order_id""").param(UUID.fromString(planId)).query().listOfRows();
    }

    private long orderCount(UUID trader) {
        return sql.sql("select count(*) from trade_order where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    /**
     * The content of the Trader's one notification titled {@code title} about the plan, once it was written after the
     * commit.
     */
    private String notification(String token, String planId, String title) {
        AtomicReference<List<String>> contents = new AtomicReference<>(List.of());
        await("the notification '" + title + "' of " + planId)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    contents.set(notifications(token, planId, title));
                    return !contents.get().isEmpty();
                });
        assertThat(contents.get()).hasSize(1);
        return contents.get().getFirst();
    }

    /** The contents of the Trader's notifications titled {@code title} about the plan, as the API answers now. */
    private List<String> notifications(String token, String planId, String title) throws Exception {
        return JsonPath.read(
                json(call(token, get("/api/v1/notifications").param("pageSize", "100"), null)
                        .andExpect(status().isOk())),
                "$.items[?(@.referenceId == '" + planId + "' && @.title == '" + title + "')].content");
    }

    /**
     * {@code count} of the ACTIVE Spot LIMIT LONG plans the loader gives demo.free, in the order of the data file:
     * entry, stop loss and take profit at its factors of the current Spot price, its expiry and note.
     */
    private void loaderPlansOfDemoFree(int count) throws Exception {
        JsonNode plans = data.freePlans();
        for (int i = 0; i < count; i++) {
            call(free, post(PLANS), loaderBody(plans.get("symbols").get(i).asString(), true))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }
    }

    /** A plan of the loader on {@code symbol}, saved as a draft (or activated) by demo.free; answers its id. */
    private String loaderPlan(String symbol, boolean activate) throws Exception {
        return id(call(free, post(PLANS), loaderBody(symbol, activate)).andExpect(status().isCreated()));
    }

    private String loaderBody(String symbol, boolean activate) {
        JsonNode plans = data.freePlans();
        BigDecimal price = new BigDecimal(BTC.equals(symbol) ? "100000" : "4000");
        String body = spot(
                symbol,
                factor(price, plans, "entryFactor"),
                factor(price, plans, "stopLossFactor"),
                factor(price, plans, "takeProfitFactor"),
                null,
                activate);
        body = withField(body, "expiresAt", "\"" + plans.get("expiresAt").asString() + "\"");
        return withField(body, "note", "\"" + plans.get("note").asString() + "\"");
    }

    private static String factor(BigDecimal price, JsonNode plans, String field) {
        return price.multiply(new BigDecimal(plans.get(field).asString()))
                .setScale(2, RoundingMode.DOWN)
                .toPlainString();
    }

    private String futures(
            String entryType, String entry, String stop, String takeProfit, int leverage, boolean activate) {
        return "{\"pairId\": \"" + pairs.get(BTC) + "\", \"market\": \"FUTURES\", \"direction\": \"LONG\","
                + " \"entryType\": \"" + entryType + "\"" + (entry == null ? "" : ", \"entryPrice\": " + entry)
                + ", \"stopLoss\": " + stop + ", \"takeProfit\": " + takeProfit + ", \"leverage\": " + leverage
                + ", \"activate\": " + activate + "}";
    }

    private String spot(String symbol, String entry, String stop, String takeProfit, String risk, boolean activate) {
        return "{\"pairId\": \"" + pairs.get(symbol) + "\", \"market\": \"SPOT\", \"direction\": \"LONG\","
                + " \"entryType\": \"LIMIT\", \"entryPrice\": " + entry + ", \"stopLoss\": " + stop
                + ", \"takeProfit\": " + takeProfit + (risk == null ? "" : ", \"riskPercent\": " + risk)
                + ", \"activate\": " + activate + "}";
    }

    private String spotMarket(String symbol, String stop, String takeProfit, boolean activate) {
        return "{\"pairId\": \"" + pairs.get(symbol) + "\", \"market\": \"SPOT\", \"direction\": \"LONG\","
                + " \"entryType\": \"MARKET\", \"stopLoss\": " + stop + ", \"takeProfit\": " + takeProfit
                + ", \"activate\": " + activate + "}";
    }

    /** {@code body} with one more field, its value written as JSON. */
    private static String withField(String body, String field, String value) {
        return body.substring(0, body.length() - 1) + ", \"" + field + "\": " + value + "}";
    }

    /** The BTCUSDT Futures last and mark price at the clock's instant, as the streams send them. */
    private void futuresLast(String price) {
        StreamedPrices streamed = new StreamedPrices(prices);
        streamed.last(MarketType.FUTURES, BTC, new BigDecimal(price), clock.instant());
        streamed.mark(BTC, new BigDecimal(price), clock.instant());
    }

    private void spotLast(String symbol, String price) {
        new StreamedPrices(prices).last(MarketType.SPOT, symbol, new BigDecimal(price), clock.instant());
    }

    /** Sets {@code SLIPPAGE_BPS} as an administrator does on SCR-43; {@link #removeWhatTheTestWrote} sets it back. */
    private void slippage(String basisPoints) throws Exception {
        slippageChanged = true;
        changeSetting("SLIPPAGE_BPS", basisPoints);
    }

    private void changeSetting(String key, String value) throws Exception {
        call(admin, put("/api/v1/admin/settings/" + key), "{\"value\": \"" + value + "\"}")
                .andExpect(status().isOk());
    }

    /** Signs in as the web app does and answers the {@code Authorization} header value. */
    private String signIn(String email, String password) throws Exception {
        String body = json(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"))
                .andExpect(status().isOk()));
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String json(ResultActions response) throws Exception {
        return response.andReturn().getResponse().getContentAsString();
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(json(created), "$.id");
    }

    private static List<String> statuses(String plan) {
        return JsonPath.read(plan, "$.statusHistory[*].status");
    }

    /** The warnings of a panel or plan, type to severity. */
    private static Map<String, String> severities(String plan) {
        List<String> types = JsonPath.read(plan, "$.warnings[*].type");
        List<String> levels = JsonPath.read(plan, "$.warnings[*].severity");
        Map<String, String> severities = new LinkedHashMap<>();
        for (int i = 0; i < types.size(); i++) {
            severities.put(types.get(i), levels.get(i));
        }
        return severities;
    }

    /** A decimal at {@code path}; a filter path answers a list, whose one element is compared. */
    private static void assertDecimal(String json, String path, String expected) {
        Object value = JsonPath.read(json, path);
        if (value instanceof List<?> list) {
            assertThat(list).as(path).hasSize(1);
            value = list.getFirst();
        }
        assertThat(value).as(path).isNotNull();
        assertThat(new BigDecimal(String.valueOf(value))).as(path).isEqualByComparingTo(expected);
    }

    private static Instant minute(long n) {
        return MINUTE_0.plus(Duration.ofMinutes(n));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        MutableTestClock wf01Clock() {
            return new MutableTestClock(START);
        }
    }
}
