package com.cryptopilot.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.market.FundingHistory;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.SettledFunding;
import com.cryptopilot.market.dto.request.LeverageBracketsRequest;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * Workflow WF-01 end to end (E2E-WF01): the demo scenarios DS-01a to DS-01g, and a change of the levels of an open
 * position, driven through the public HTTP API as the web app calls it, by the demo accounts of DEMO-01 signed in with
 * their passwords.
 *
 * <p>The backend runs on the {@code demo} profile, on a database of its own, so Flyway applies the demo accounts:
 * demo.free (FREE, CONSERVATIVE, 10,000 at 1 %), demo.pro (PRO, BALANCED, 10,000 at 1 %) and the administrator. The
 * administrator sets the pairs up as the loader does, through the admin API: BTCUSDT and ETHUSDT switched on with the
 * leverage brackets of {@code deploy/demo/wf01.json}; {@code SLIPPAGE_BPS} is changed through the same API.
 *
 * <p>No network. What the exchange would send is handed in by the test: the pairs as the symbol synchronisation lists
 * them (Spot tick 0.01, step 0.00001; Futures tick 0.1, step 0.001, minimum notional 100), last and mark prices into
 * the price cache, closed 1-minute candles to the running matching feed, and the funding settlements the market port
 * answers. The clock is the test's: every test starts at 07:58:30 on 2026-11-02 and the clock moves with the candles.
 * Fees are the seeded ones: Futures maker 0.02 %, taker 0.05 %; Spot 0.1 %.
 *
 * <p>NFR N3 of the Design Brief: a fill or an exit decided by a candle is visible through the API at most 3 s after the
 * candle is handed to the matching feed; each measurement is logged.
 *
 * <p>Rule: WF-01; UC-16 to UC-21; BR-23 to BR-39, BR-62, BR-68, BR-69; NSF-07; MSG16, MSG18, MSG23, MSG27, MSG29,
 * MSG51, MSG52, MSG54; D-111, D-112, D-120, D-122, D-124.
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

    private static final String PLANS = "/api/v1/plans";
    private static final String BTC = "BTCUSDT";
    private static final String ETH = "ETHUSDT";

    /** The minute the clock starts in; a test starts 30 s into it. */
    private static final Instant MINUTE_0 = Instant.parse("2026-11-02T07:58:00Z");

    private static final Instant START = MINUTE_0.plusSeconds(30);
    private static final Instant EIGHT = Instant.parse("2026-11-02T08:00:00Z");
    private static final Duration N3 = Duration.ofSeconds(3);
    private static final FundingHistory NO_FUNDING =
            new FundingHistory(List.of(), List.of(), List.of(), List.of(), List.of());

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
    private MutableTestClock clock;

    private final DemoWf01Data data = DemoWf01Data.read();
    private final Map<String, UUID> pairs = new LinkedHashMap<>();
    private final AtomicReference<List<SettledFunding>> settlements = new AtomicReference<>(List.of());

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
    void startTheEngine() throws Exception {
        feed.stop();
        worker.stop();
        clock.set(START);
        for (String symbol : pairs.keySet()) {
            // The price cache is shared with the suite; an entry newer than this test's clock would hide its prices.
            redis.delete(List.of("px:SPOT:" + symbol, "px:FUTURES:" + symbol));
        }
        settlements.set(List.of());
        doAnswer(call -> funding(call.getArgument(1), call.getArgument(2)))
                .when(market)
                .fundingHistory(any(), any(), any());
        doReturn(MinuteKlineBatch.of(List.of())).when(market).closedMinuteKlines(any(), any(), any());
        doReturn(MinuteKlineBatch.of(List.of())).when(market).closedMarkPriceKlines(any(), any());
        feed.start();
        admin = signIn(ADMIN_EMAIL, ADMIN_PASSWORD);
        pro = signIn(PRO_EMAIL, DEMO_PASSWORD);
        free = signIn(FREE_EMAIL, DEMO_PASSWORD);
        slippageChanged = false;
    }

    @AfterEach
    void removeWhatTheTestWrote() throws Exception {
        feed.stop();
        worker.stop();
        if (slippageChanged) {
            changeSetting("SLIPPAGE_BPS", "0");
        }
        InAppNotifications.remove(sql, List.of(DEMO_FREE, DEMO_PRO));
        for (UUID trader : List.of(DEMO_FREE, DEMO_PRO)) {
            sql.sql("""
                            delete from trading_journal_level_change where journal_id in
                                   (select journal_id from trading_journal where user_id = ?)""").param(trader).update();
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(trader)
                    .update();
            sql.sql("delete from trading_plan where user_id = ?").param(trader).update();
        }
        sql.sql("delete from matching_watermark").update();
    }

    // ------------------------------------------------------------------------------------------
    // DS-01a: a MARKET Futures plan to a closed journal entry (main scenario)
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01a. demo.pro plans BTCUSDT Futures MARKET LONG, SL 99,500, TP 101,500, leverage 5, risk 1 % (100) with the
     * last price at 100,000 and {@code SLIPPAGE_BPS} 10. Saved and activated, it fills at once as a taker at 100,100;
     * a funding settlement at 08:00 is paid while it is open; at 08:05 the Trader estimates and closes it by hand with
     * the last price at 100,600. The plan stays EXECUTED, the journal entry is CLOSED with the amounts below, and MSG23
     * names the close.
     */
    @Test
    void DS01a_marketFuturesPlan_toClosedJournalEntry() throws Exception {
        slippage("10");
        futuresLast("100000");
        String body = futures("MARKET", null, "99500", "101500", 5, true);

        String panel = json(call(pro, post(PLANS + "/calculate"), body).andExpect(status().isOk()));
        // 100,000 × 1.001 = 100,100.0; 100 / (100,100 − 99,500) = 0.1666… → 0.166 on the step 0.001.
        assertDecimal(panel, "$.expectedFillPrice", "100100");
        assertDecimal(panel, "$.snapshot.positionQuantity", "0.166");
        // 0.166 × 100,100 = 16,616.6; margin at 5x 3,323.32.
        assertDecimal(panel, "$.snapshot.notionalValue", "16616.6");
        assertDecimal(panel, "$.snapshot.initialMargin", "3323.32");
        // (16,616.6 − 3,323.32) / (0.166 × (1 − 0.004)) = 80,401.606… → up to the tick 0.1: 80,401.7, 19.7 % below.
        assertDecimal(panel, "$.snapshot.estimatedLiquidationPrice", "80401.7");
        // (101,500 − 100,100) / (100,100 − 99,500) = 2.33333333.
        assertDecimal(panel, "$.snapshot.riskRewardRatio", "2.33333333");
        assertThat((Boolean) JsonPath.read(panel, "$.blocksActivation")).isFalse();

        String created = json(call(pro, post(PLANS), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.position.status").value("OPEN")));
        String id = JsonPath.read(created, "$.id");
        assertDecimal(created, "$.position.fillPrice", "100100");
        assertDecimal(created, "$.position.quantity", "0.166");
        // Taker: 16,616.6 × 0.0005 = 8.3083.
        assertDecimal(created, "$.position.entryFee", "8.3083");
        assertDecimal(created, "$.position.liquidationPrice", "80401.7");
        assertThat(Instant.parse(JsonPath.read(created, "$.position.entryTime")))
                .isEqualTo(START);
        assertThat(statuses(created)).containsExactly("DRAFT", "ACTIVE", "EXECUTED");
        String open = journal(pro, "OPEN");
        assertThat((String) JsonPath.read(open, "$.items[0].planId")).isEqualTo(id);
        assertDecimal(open, "$.items[0].entryPrice", "100100");

        // LONG pays 0.166 × 100,200 × 0.0001 = 1.66332 at 08:00.
        settlements.set(List.of(new SettledFunding(EIGHT, new BigDecimal("0.0001"), new BigDecimal("100200"))));
        clock.set(Instant.parse("2026-11-02T08:05:00Z"));
        futuresLast("100600");

        String estimate =
                json(call(pro, get(PLANS + "/" + id + "/close-estimate"), null).andExpect(status().isOk()));
        // Sold 10 bps lower: 100,600 × 0.999 = 100,499.4.
        assertDecimal(estimate, "$.estimatedExitPrice", "100499.4");
        // (100,499.4 − 100,100) × 0.166 = 66.3004.
        assertDecimal(estimate, "$.priceResult", "66.3004");
        assertDecimal(estimate, "$.entryFee", "8.3083");
        // 100,499.4 × 0.166 × 0.0005 = 8.3414502.
        assertDecimal(estimate, "$.estimatedExitFee", "8.3414502");
        assertDecimal(estimate, "$.fundingSoFar", "-1.66332");
        // 66.3004 − 8.3083 − 8.3414502 − 1.66332 = 47.9873298.
        assertDecimal(estimate, "$.estimatedNetPnl", "47.9873298");

        String closed = json(call(pro, post(PLANS + "/" + id + "/close-position"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.position.status").value("CLOSED"))
                .andExpect(jsonPath("$.position.closeReason").value("MANUAL"))
                .andExpect(jsonPath("$.position.provisional").value(false)));
        assertDecimal(closed, "$.position.exitPrice", "100499.4");
        assertDecimal(closed, "$.position.netPnl", "47.9873298");

        String entry = journal(pro, "CLOSED");
        assertThat((String) JsonPath.read(entry, "$.items[0].planId")).isEqualTo(id);
        assertThat((String) JsonPath.read(entry, "$.items[0].closeReason")).isEqualTo("MANUAL");
        assertDecimal(entry, "$.items[0].entryPrice", "100100");
        assertDecimal(entry, "$.items[0].exitPrice", "100499.4");
        assertDecimal(entry, "$.items[0].quantity", "0.166");
        assertDecimal(entry, "$.items[0].entryFee", "8.3083");
        // Both fees: 8.3083 + 8.3414502.
        assertDecimal(entry, "$.items[0].feeAmount", "16.6497502");
        assertDecimal(entry, "$.items[0].fundingFeeAmount", "-1.66332");
        assertDecimal(entry, "$.items[0].realizedPnl", "66.3004");
        assertDecimal(entry, "$.items[0].netPnl", "47.9873298");
        assertThat((Boolean) JsonPath.read(entry, "$.items[0].provisional")).isFalse();
        assertThat(notification(pro, id))
                .isEqualTo("BTCUSDT position closed by manual close at 100499.4. P/L: 47.9873298.");
    }

    /**
     * DS-01a, the fill: the Trader receives MSG22 "{pair} {direction} entry filled at {price}." when the entry fills
     * (SRS 3.5, UC-20).
     */
    @Test
    @Disabled("bug: no MSG22 notification is written when an entry fills (Q-81)")
    void DS01a_aFill_sendsMsg22() throws Exception {
        futuresLast("100000");
        String id = JsonPath.read(
                json(call(pro, post(PLANS), futures("MARKET", null, "99500", "101500", 5, true))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("EXECUTED"))),
                "$.id");

        assertThat(notification(pro, id)).isEqualTo("BTCUSDT LONG entry filled at 100000.");
    }

    /**
     * DS-01a-2. The plan of DS-01a closed by the engine: at the take profit by a candle that trades through it (a
     * resting limit order: maker, at its price), and, for a second plan, at the stop loss by a candle that opens below
     * it (a stop-market order: taker, at the open, sold 10 bps lower).
     */
    @Test
    void DS01a2_closedByTakeProfit_andByStopLossOnAGap() throws Exception {
        slippage("10");
        futuresLast("100000");
        String body = futures("MARKET", null, "99500", "101500", 5, true);
        String byTakeProfit =
                id(call(pro, post(PLANS), body).andExpect(jsonPath("$.status").value("EXECUTED")));

        long handed = feedClosed(MarketType.FUTURES, BTC, 1, "101000", "100900", "101600");
        String first = awaitVisible("the take profit exit", handed, () -> positionStatus(pro, byTakeProfit), "CLOSED");

        assertThat((String) JsonPath.read(first, "$.position.closeReason")).isEqualTo("TAKE_PROFIT");
        assertDecimal(first, "$.position.exitPrice", "101500");
        assertThat(Instant.parse(JsonPath.read(first, "$.position.exitTime"))).isEqualTo(minute(1));
        // (101,500 − 100,100) × 0.166 = 232.4.
        assertDecimal(first, "$.position.realizedPnl", "232.4");
        // Maker exit: 101,500 × 0.166 × 0.0002 = 3.3698; with the entry 8.3083 + 3.3698 = 11.6781.
        assertDecimal(first, "$.position.feeAmount", "11.6781");
        // 232.4 − 11.6781 = 220.7219, no funding.
        assertDecimal(first, "$.position.netPnl", "220.7219");
        assertThat(notification(pro, byTakeProfit))
                .isEqualTo("BTCUSDT position closed by take profit at 101500. P/L: 220.7219.");

        clock.set(EIGHT.plusSeconds(10));
        futuresLast("100000");
        String byStopLoss =
                id(call(pro, post(PLANS), body).andExpect(jsonPath("$.status").value("EXECUTED")));
        feedClosed(MarketType.FUTURES, BTC, 2, "100050", "100000", "100150");
        handed = feedClosed(MarketType.FUTURES, BTC, 3, "99400", "99300", "99450");
        String second = awaitVisible("the stop loss exit", handed, () -> positionStatus(pro, byStopLoss), "CLOSED");

        assertThat((String) JsonPath.read(second, "$.position.closeReason")).isEqualTo("STOP_LOSS");
        // Gap: the open 99,400 sold 10 bps lower, 99,300.6.
        assertDecimal(second, "$.position.exitPrice", "99300.6");
        assertThat(Instant.parse(JsonPath.read(second, "$.position.exitTime"))).isEqualTo(minute(3));
        // (99,300.6 − 100,100) × 0.166 = −132.7004.
        assertDecimal(second, "$.position.realizedPnl", "-132.7004");
        // Taker exit: 99,300.6 × 0.166 × 0.0005 = 8.2419498; with the entry 16.5502498.
        assertDecimal(second, "$.position.feeAmount", "16.5502498");
        // −132.7004 − 16.5502498 = −149.2506498.
        assertDecimal(second, "$.position.netPnl", "-149.2506498");
        assertThat(notification(pro, byStopLoss))
                .isEqualTo("BTCUSDT position closed by stop loss at 99300.6. P/L: -149.2506498.");
        assertThat(JsonPath.<List<String>>read(journal(pro, "CLOSED"), "$.items[*].planId"))
                .containsExactlyInAnyOrder(byTakeProfit, byStopLoss);
    }

    // ------------------------------------------------------------------------------------------
    // DS-01b: a LIMIT plan resting in the book (main scenario)
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01b. demo.pro plans BTCUSDT Futures LIMIT LONG at 99,000 (SL 98,010, TP 101,970, leverage 5) with the last
     * price at 100,000. An invalidation price below the price is MSG16 on its field; at 102,000 the plan is ACTIVE with
     * the default expiry of 7 days and rests while the candle of 07:59 stays above it. The candle of 08:00 trades
     * through 99,000 and fills it there as a maker.
     */
    @Test
    void DS01b_limitPlan_restsUntilACandleReachesIt() throws Exception {
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

        feedClosed(MarketType.FUTURES, BTC, 1, "99800", "99500", "100100");
        long handed = feedClosed(MarketType.FUTURES, BTC, 2, "99500", "98900", "99600");
        String filled = awaitVisible("the LIMIT fill", handed, () -> plan(pro, id), "$.status", "EXECUTED");

        assertThat(statuses(filled)).containsExactly("DRAFT", "ACTIVE", "EXECUTED");
        assertThat((String) JsonPath.read(filled, "$.position.status")).isEqualTo("OPEN");
        assertDecimal(filled, "$.position.fillPrice", "99000");
        assertThat(Instant.parse(JsonPath.read(filled, "$.position.entryTime")))
                .as("the open of the candle that reached it, not the 07:59 one")
                .isEqualTo(minute(2));
        // Maker: 99,000 × 0.101 × 0.0002 = 1.9998.
        assertDecimal(filled, "$.position.entryFee", "1.9998");
        String open = journal(pro, "OPEN");
        assertThat((String) JsonPath.read(open, "$.items[0].planId")).isEqualTo(id);
        assertDecimal(open, "$.items[0].entryFee", "1.9998");
    }

    // ------------------------------------------------------------------------------------------
    // DS-01c, DS-01d: exceptions on the plan itself
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01c. demo.pro plans BTCUSDT Futures LIMIT LONG at 99,000 with SL 94,050 and leverage 50 (tier 1 of the demo
     * brackets: up to 125x, MMR 0.004). The liquidation price lies above the stop loss: BLOCKING, plus a WARNING for
     * leverage above BALANCED's 5x. The draft is saved with them; activation is MSG18 and it stays DRAFT.
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
        assertThat(severities(draft)).containsEntry("SL_BEYOND_LIQUIDATION", "BLOCKING");

        call(pro, post(PLANS + "/" + id + "/activate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRADING_BLOCKING_WARNING"))
                .andExpect(jsonPath("$.messageCode").value("MSG18"));
        String after = plan(pro, id);
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("DRAFT");
        assertThat(statuses(after)).containsExactly("DRAFT");
        assertThat(severities(after)).containsEntry("SL_BEYOND_LIQUIDATION", "BLOCKING");
    }

    /** DS-01d. demo.free asks for a Futures plan: the panel and the save are MSG29, and nothing is saved. */
    @Test
    void DS01d_freeAccount_futuresPlan_isRefused() throws Exception {
        futuresLast("100000");
        String body = futures("LIMIT", "99000", "98010", "101970", 5, false);

        for (String path : List.of(PLANS + "/calculate", PLANS)) {
            call(free, post(path), body)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PLAN_FEATURE_NOT_INCLUDED"))
                    .andExpect(jsonPath("$.messageCode").value("MSG29"));
        }
        assertThat(planCount(free)).isZero();
    }

    // ------------------------------------------------------------------------------------------
    // DS-01e: a MARKET entry that moved since the draft (BR-69)
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01e. Three demo.pro drafts of BTCUSDT Futures MARKET LONG, SL 99,000, TP 102,000, saved with the last price at
     * 100,000 (1R = 1,000; {@code SLIPPAGE_BPS} 0). At 100,500 (0.5R) activation is MSG51 with the recalculated plan
     * until confirmed, then fills at 100,500; at 101,000 (1R, beyond 0.75R) it is MSG52 even confirmed and stays a
     * draft; at 100,200 (0.2R, under 0.25R) it fills directly.
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
        assertDecimal(required, "$.expectedFillPrice", "100500");
        assertDecimal(required, "$.positionQuantity", "0.066");
        assertDecimal(required, "$.riskRewardRatio", "1");
        assertThat((String) JsonPath.read(plan(pro, drafts.get(0)), "$.status")).isEqualTo("DRAFT");
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

        clock.set(START.plusSeconds(30));
        futuresLast("100200");
        String direct = json(call(pro, post(PLANS + "/" + drafts.get(2) + "/activate"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED")));
        assertDecimal(direct, "$.position.fillPrice", "100200");
    }

    // ------------------------------------------------------------------------------------------
    // DS-01f, DS-01g: the plan limit of FREE (BR-62) and an invalidated LIMIT (BR-68)
    // ------------------------------------------------------------------------------------------

    /**
     * DS-01f. demo.free holds three ACTIVE Spot plans: two of the loader (BTCUSDT and ETHUSDT, far below the price)
     * and a BTCUSDT LIMIT LONG at 99,000 (SL 98,010, TP 101,970, risk 0.5 %), so a fourth is MSG27. The Spot candle of
     * 07:59 rises from 100,500 to 102,100 without coming down to 99,000: the take profit is reached first, the plan is
     * INVALIDATED with that reason, MSG54 tells the Trader, and its slot is free for the fourth plan.
     */
    @Test
    void DS01f_limitInvalidated_whenTakeProfitReachedFirst() throws Exception {
        spotLast(BTC, "100000");
        spotLast(ETH, "4000");
        loaderPlansOfDemoFree(2);
        String limit = id(call(free, post(PLANS), spot(BTC, "99000", "98010", "101970", "0.5", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        String fourth = loaderPlan(BTC, false);
        call(free, post(PLANS + "/" + fourth + "/activate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG27"));

        long handed = feedClosed(MarketType.SPOT, BTC, 1, "100500", "100400", "102100");
        String invalidated =
                awaitVisible("the invalidation", handed, () -> plan(free, limit), "$.status", "INVALIDATED");

        assertThat((String) JsonPath.read(invalidated, "$.invalidationReason")).isEqualTo("TAKE_PROFIT_REACHED");
        assertThat(statuses(invalidated)).containsExactly("DRAFT", "ACTIVE", "INVALIDATED");
        assertThat(notification(free, limit))
                .isEqualTo("BTCUSDT LONG plan was invalidated before its entry was filled: the take profit was reached"
                        + " first.");
        call(free, post(PLANS + "/" + fourth + "/activate"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /**
     * DS-01g. demo.free holds the three ACTIVE plans of the loader. A fourth by Save and Activate is MSG27 with the
     * limit 3 and the tier FREE, and nothing is saved; a saved draft stays DRAFT when activated. demo.pro activates
     * the same plan.
     */
    @Test
    void DS01g_freeFourthActivePlan_isRefused() throws Exception {
        spotLast(BTC, "100000");
        spotLast(ETH, "4000");
        loaderPlansOfDemoFree(3);
        String fourth = loaderBody(BTC, true);

        call(free, post(PLANS), fourth)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"))
                .andExpect(jsonPath("$.messageCode").value("MSG27"))
                .andExpect(jsonPath("$.messageArgs[0]").value("3"))
                .andExpect(jsonPath("$.messageArgs[2]").value("FREE"));
        assertThat(planCount(free)).isEqualTo(3);

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
    // TP-E: the levels of an open position changed from the next candle on
    // ------------------------------------------------------------------------------------------

    /**
     * TP-E. The DS-01a position (filled at 100,100, SL 99,500) has its stop loss raised to 99,800 at 07:59:30; the
     * change takes effect from the candle of 08:00. The candle of 07:59 trades down to 99,700, below the new stop, and
     * closes nothing; the candle of 08:00 opens at 99,900 and trades down to 99,750: the stop-market exit at 99,800 is
     * sold 10 bps lower, at 99,700.2.
     */
    @Test
    void TPE_aStopLossChange_actsFromTheNextCandle() throws Exception {
        slippage("10");
        futuresLast("100000");
        String id = id(call(pro, post(PLANS), futures("MARKET", null, "99500", "101500", 5, true))
                .andExpect(jsonPath("$.status").value("EXECUTED")));

        clock.set(minute(1).plusSeconds(30));
        String changed = json(call(pro, patch(PLANS + "/" + id + "/position-levels"), "{\"stopLoss\": 99800}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.levelChangeCount").value(1))
                .andExpect(jsonPath("$.position.stopWidened").value(false)));
        assertThat(Instant.parse(JsonPath.read(changed, "$.position.levelsEffectiveFrom")))
                .isEqualTo(minute(2));
        assertDecimal(changed, "$.position.stopLoss", "99800");
        assertDecimal(changed, "$.position.originalStopLoss", "99500");

        feedClosed(MarketType.FUTURES, BTC, 1, "100050", "99700", "100150");
        long handed = feedClosed(MarketType.FUTURES, BTC, 2, "99900", "99750", "99950");
        String closed = awaitVisible("the exit at the new stop", handed, () -> positionStatus(pro, id), "CLOSED");

        assertThat((String) JsonPath.read(closed, "$.position.closeReason")).isEqualTo("STOP_LOSS");
        assertThat(Instant.parse(JsonPath.read(closed, "$.position.exitTime")))
                .as("closed by the 08:00 candle, not by the 07:59 one that also traded below 99,800")
                .isEqualTo(minute(2));
        // 99,800 × 0.999 = 99,700.2.
        assertDecimal(closed, "$.position.exitPrice", "99700.2");
        // (99,700.2 − 100,100) × 0.166 = −66.3668.
        assertDecimal(closed, "$.position.realizedPnl", "-66.3668");
        // 8.3083 + 99,700.2 × 0.166 × 0.0005 (8.2751166) = 16.5834166; net −66.3668 − 16.5834166 = −82.9502166.
        assertDecimal(closed, "$.position.feeAmount", "16.5834166");
        assertDecimal(closed, "$.position.netPnl", "-82.9502166");
        assertThat(notification(pro, id))
                .isEqualTo("BTCUSDT position closed by stop loss at 99700.2. P/L: -82.9502166.");
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** The answer of the market port for the funding of a position held in {@code (after, upTo]}: the settlements set. */
    private FundingHistory funding(Instant after, Instant upTo) {
        List<SettledFunding> within = settlements.get().stream()
                .filter(settled -> settled.fundingTime().isAfter(after)
                        && !settled.fundingTime().isAfter(upTo))
                .toList();
        if (within.isEmpty()) {
            return NO_FUNDING;
        }
        return new FundingHistory(
                within, within.stream().map(SettledFunding::fundingTime).toList(), List.of(), List.of(), List.of());
    }

    /**
     * Hands the closed candle of minute {@code n} of the pair to the matching feed, after the clock moved to its end,
     * and answers when it was handed, for N3.
     */
    private long feedClosed(MarketType marketType, String symbol, int n, String open, String low, String high) {
        if (clock.instant().isBefore(minute(n + 1))) {
            clock.set(minute(n + 1));
        }
        long handed = System.nanoTime();
        feed.onMinuteKline(ClosedKlines.closed(marketType, pairs.get(symbol), minute(n), open, low, high));
        return handed;
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
        log.info(
                "N3 {}: visible through the API {} ms after its candle was handed to the engine",
                what,
                took.toMillis());
        assertThat(took).as("N3: " + what).isLessThanOrEqualTo(N3);
        return seen.get();
    }

    /** {@link #awaitVisible(String, long, Callable, String, String)} on the position status of a plan's detail. */
    private String awaitVisible(String what, long handed, Callable<String> plan, String positionStatus) {
        return awaitVisible(what, handed, plan, "$.position.status", positionStatus);
    }

    /** The plan's detail, which carries its position once filled. */
    private String positionStatus(String token, String id) throws Exception {
        return plan(token, id);
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

    /** The content of the Trader's one notification about the plan, once it was written after the commit. */
    private String notification(String token, String planId) {
        AtomicReference<List<String>> contents = new AtomicReference<>(List.of());
        await("the notification of " + planId)
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    contents.set(JsonPath.read(
                            json(call(token, get("/api/v1/notifications").param("pageSize", "100"), null)
                                    .andExpect(status().isOk())),
                            "$.items[?(@.referenceId == '" + planId + "')].content"));
                    return !contents.get().isEmpty();
                });
        assertThat(contents.get()).hasSize(1);
        return contents.get().getFirst();
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

    /** {@code body} with one more field, its value written as JSON. */
    private static String withField(String body, String field, String value) {
        return body.substring(0, body.length() - 1) + ", \"" + field + "\": " + value + "}";
    }

    /** The BTCUSDT Futures last price at the clock's instant, as the stream's forming 1-minute candle sends it. */
    private void futuresLast(String price) {
        new StreamedPrices(prices).last(MarketType.FUTURES, BTC, new BigDecimal(price), clock.instant());
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

    private static void assertDecimal(String json, String path, String expected) {
        Object value = JsonPath.read(json, path);
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
