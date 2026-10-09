package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.paper.job.PaperMatchingWorker;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.service.PaperMatchingService;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Spot paper orders end to end: MARKET and LIMIT placement against the latest-price cache, the balances, fees and
 * ledger they leave, cancels, the idempotency key, the refusals, and a waiting LIMIT order filled by the matching
 * engine from a 1-minute candle.
 *
 * <p>Each test trades a pair of its own, {@code PX…USDT} with a tick of 0.01, a step of 0.001 and a minimum notional
 * of 5, priced through the real cache. Nothing is rolled back, because the engine fills on its own thread and
 * transaction: {@link #removeRows()} deletes every row the test wrote.
 *
 * <p>Rule: TR-02, TR-04; BR-23, BR-30; NSF-03; D-77.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PaperOrderApiTest {

    private static final String PAPER = "/api/v1/paper";
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
    private PaperMatchingWorker worker;

    @Autowired
    private PaperMatchingService matching;

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "PX" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, false, "TRADING", null, 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5
                         where pair_id = ?""").param(pair).update();
    }

    @AfterEach
    void removeRows() {
        for (UUID trader : traders) {
            String account = "(select account_id from paper_account where user_id = ?)";
            for (String table :
                    List.of("paper_fill", "paper_order", "paper_ledger_entry", "paper_transfer", "paper_balance")) {
                sql.sql("delete from " + table + " where account_id = " + account)
                        .param(trader)
                        .update();
            }
            sql.sql("delete from paper_account where user_id = ?").param(trader).update();
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        sql.sql("delete from paper_matching_watermark where pair_id = ?")
                .param(pair)
                .update();
        UUID base = sql.sql("select base_coin_id from crypto_pair where pair_id = ?")
                .param(pair)
                .query(UUID.class)
                .single();
        sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        sql.sql("delete from coin where coin_id = ?").param(base).update();
        // The seed and schema tests expect no coin they did not write, so the coins an opening stored go again.
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

    // ------------------------------------------------------------------------------------------
    // MARKET
    // ------------------------------------------------------------------------------------------

    @Test
    void TR02_aMarketBuy_executesAtTheLastPrice_payingTheQuote_andTheFeeInTheCoinBought() throws Exception {
        UUID trader = trader();
        price("100");

        place(trader, market("BUY", "\"quantity\": 2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value(symbol))
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.type").value("MARKET"))
                .andExpect(jsonPath("$.avgPrice").value(100.0))
                .andExpect(jsonPath("$.executedQuantity").value(2.0))
                .andExpect(jsonPath("$.cumQuote").value(200.0))
                .andExpect(jsonPath("$.closedAt").exists());

        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9800");
        assertThat(spot(trader, base()))
                .as("2 bought, 0.1% fee taken from them")
                .isEqualByComparingTo("1.998");
        as(trader, get(PAPER + "/fills"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].liquidity").value("TAKER"))
                .andExpect(jsonPath("$.items[0].source").value("LIVE"))
                .andExpect(jsonPath("$.items[0].fee").value(0.002))
                .andExpect(jsonPath("$.items[0].feeAsset").value(base()));
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void TR02_aMarketBuyByTotal_buysWhatTheAmountBuysOnTheStep_andASaleBringsTheQuoteLessItsFee() throws Exception {
        UUID trader = trader();
        price("3");

        place(trader, market("BUY", "\"quoteOrderQty\": 50"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").doesNotExist())
                .andExpect(jsonPath("$.quoteOrderQty").value(50.0))
                .andExpect(jsonPath("$.executedQuantity").value(16.666))
                .andExpect(jsonPath("$.cumQuote").value(49.998));
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9950.002");

        price("4");
        place(trader, market("SELL", "\"quantity\": 10"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.cumQuote").value(40.0));

        assertThat(spot(trader, "USDT")).as("40 less the 0.04 fee").isEqualByComparingTo("9989.962");
        assertThat(spot(trader, base())).isEqualByComparingTo("6.649334");
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void NSF03_aMarketOrderWithoutACurrentPrice_isRefused_andNothingIsStored() throws Exception {
        UUID trader = trader();

        place(trader, market("BUY", "\"quantity\": 1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_PRICE_UNAVAILABLE"));

        assertThat(orderCount(trader)).isZero();
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("10000");
    }

    @Test
    void TR04_anOrderTheWalletCannotPay_isRefused_namingTheCoin_andNothingIsStored() throws Exception {
        UUID trader = trader();
        price("100");

        place(trader, market("BUY", "\"quantity\": 101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.messageArgs[0]").value("USDT"));
        place(trader, limit("SELL", "200", "1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageArgs[0]").value(base()));

        assertThat(orderCount(trader)).isZero();
        assertLedgerAddsUpToTheBalances(trader);
    }

    // ------------------------------------------------------------------------------------------
    // LIMIT
    // ------------------------------------------------------------------------------------------

    @Test
    void TR02_aLimitBuyBelowThePrice_waitsWithItsCostLocked_andACancelGivesItBack() throws Exception {
        UUID trader = trader();
        price("100");

        String id = id(place(trader, limit("BUY", "90.004", "2.0009", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.timeInForce").value("GTC"))
                .andExpect(jsonPath("$.price").value(90.0))
                .andExpect(jsonPath("$.quantity").value(2.0))
                .andExpect(jsonPath("$.closedAt").doesNotExist()));
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("180");
        as(trader, get(PAPER + "/orders/open").param("pairId", pair.toString()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(id));

        as(trader, post(PAPER + "/orders/" + id + "/cancel"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        assertThat(locked(trader, "USDT")).isZero();
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("10000");
        as(trader, get(PAPER + "/orders/open"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        as(trader, post(PAPER + "/orders/" + id + "/cancel"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_NOT_OPEN"))
                .andExpect(jsonPath("$.messageArgs[0]").value("CANCELED"));
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void TR02_aLimitTheMarketAlreadyStandsAt_executesNow_atTheLastPrice_asATaker() throws Exception {
        UUID trader = trader();
        price("100");

        place(trader, limit("BUY", "105", "1", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.avgPrice").value(100.0));

        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9900");
        assertThat(locked(trader, "USDT")).isZero();
    }

    @Test
    void TR02_anIocOrFokLimitThePriceHasNotReached_andAPostOnlyThatWouldTake_expireAtOnce() throws Exception {
        UUID trader = trader();
        price("100");

        place(trader, limit("BUY", "90", "1", "IOC"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("NOT_FILLABLE_ON_ARRIVAL"));
        place(trader, limit("BUY", "90", "1", "FOK"))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        place(trader, limit("BUY", "110", "1", "GTX"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("POST_ONLY_WOULD_TAKE"));

        assertThat(spot(trader, "USDT")).isEqualByComparingTo("10000");
        assertThat(locked(trader, "USDT")).isZero();
        as(trader, get(PAPER + "/orders").param("status", "EXPIRED"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void NSF03_withoutACurrentPrice_aGtcLimitWaits_butAnIocOneCannotBeDecided() throws Exception {
        UUID trader = trader();

        place(trader, limit("BUY", "90", "1", "IOC"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_PRICE_UNAVAILABLE"));
        place(trader, limit("BUY", "90", "1", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    // ------------------------------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------------------------------

    @Test
    void TR02_aWaitingLimit_isFilledByTheFirstCandleAfterItThatReachesIt_atItsOwnPrice_asAMaker() throws Exception {
        UUID trader = trader();
        price("100");
        String id = id(place(trader, limit("BUY", "90", "1", null)).andExpect(status().isCreated()));
        Instant next = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));

        worker.onMinuteKline(new MinuteKline(
                MarketType.SPOT, pair, next, new BigDecimal("89.5"), new BigDecimal("95"), true, next.plusSeconds(59)));

        awaitStatus(trader, id, "FILLED");
        as(trader, get(PAPER + "/orders/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avgPrice").value(90.0))
                .andExpect(jsonPath("$.closedAt").value(next.toString()));
        as(trader, get(PAPER + "/fills").param("pairId", pair.toString()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(id))
                .andExpect(jsonPath("$.items[0].liquidity").value("MAKER"))
                .andExpect(jsonPath("$.items[0].price").value(90.0))
                .andExpect(jsonPath("$.items[0].tradedAt").value(next.toString()));
        assertThat(locked(trader, "USDT")).isZero();
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9910");
        assertThat(spot(trader, base())).isEqualByComparingTo("0.999");
        assertThat(watermark()).isEqualTo(next);
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void TR02_aWaitingSellFilledFromLocked_andAFillOfAFinishedOrder_doNothingTwice() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 1")).andExpect(status().isCreated());
        String id = id(place(trader, limit("SELL", "120", "0.5", null)).andExpect(status().isCreated()));
        assertThat(locked(trader, base())).isEqualByComparingTo("0.5");
        RestingOrder resting = new RestingOrder(
                UUID.fromString(id),
                accountOf(trader),
                MarketType.SPOT,
                pair,
                OrderSide.SELL,
                new BigDecimal("120"),
                Instant.now());
        // After the MARKET fill, so the trade history lists this one first.
        Instant at = Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);

        assertThat(matching.fill(resting, at, FillSource.REPLAY)).isTrue();
        assertThat(matching.fill(resting, at, FillSource.REPLAY))
                .as("a replay finds it FILLED")
                .isFalse();

        assertThat(locked(trader, base())).isZero();
        assertThat(spot(trader, "USDT")).as("9900 + 60 - 0.06 fee").isEqualByComparingTo("9959.94");
        as(trader, get(PAPER + "/fills"), null)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].source").value("REPLAY"));
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void QT6_theWatermark_onlyMovesForward() {
        Instant later = Instant.parse("2026-10-07T08:05:00Z");

        matching.advanceWatermark(MarketType.SPOT, pair, later);
        matching.advanceWatermark(MarketType.SPOT, pair, later.minusSeconds(60));

        assertThat(matching.watermark(MarketType.SPOT, pair)).contains(later);
    }

    // ------------------------------------------------------------------------------------------
    // Idempotency, refusals, ownership
    // ------------------------------------------------------------------------------------------

    @Test
    void TR02_theSameClientOrderId_isTheSameOrder_andReusedForAnotherOrderIsAConflict() throws Exception {
        UUID trader = trader();
        price("100");
        String body = "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"BUY\", \"type\": \"LIMIT\","
                + " \"price\": 90, \"quantity\": 1, \"clientOrderId\": \"ord-1\"}";

        String id = id(place(trader, body).andExpect(status().isCreated()));
        place(trader, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id));
        place(trader, body.replace("\"price\": 90", "\"price\": 91"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));

        assertThat(orderCount(trader)).isEqualTo(1);
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("90");
    }

    @Test
    void MSG15_fieldsThatDoNotFitTheOrder_areNamedUnderErrors() throws Exception {
        UUID trader = trader();
        price("100");

        place(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"BUY\", \"type\": \"LIMIT\","
                                + " \"quoteOrderQty\": 10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG01"))
                .andExpect(jsonPath("$.errors.quantity").value("MSG01"))
                .andExpect(jsonPath("$.errors.quoteOrderQty").value("MSG15"));
        place(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"FUTURES\", \"side\": \"BUY\","
                                + " \"type\": \"STOP_MARKET\", \"quantity\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.market").value("MSG15"))
                .andExpect(jsonPath("$.errors.type").value("MSG15"));
        place(trader, market("BUY", "\"price\": 1, \"timeInForce\": \"GTC\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG15"))
                .andExpect(jsonPath("$.errors.timeInForce").value("MSG15"))
                .andExpect(jsonPath("$.errors.quantity").value("MSG01"));
        place(trader, market("BUY", "\"quantity\": 1, \"quoteOrderQty\": 10"))
                .andExpect(jsonPath("$.errors.quoteOrderQty").value("MSG15"));
        place(trader, market("SELL", "\"quoteOrderQty\": 10"))
                .andExpect(jsonPath("$.errors.quoteOrderQty").value("MSG15"));
        place(trader, market("BUY", "\"quoteOrderQty\": 4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quoteOrderQty").value("MSG15"));
        place(trader, market("BUY", "\"quantity\": 0.04"))
                .andExpect(jsonPath("$.errors.quantity").value("MSG15"));
        place(trader, limit("BUY", "90", "0.0009", null))
                .andExpect(jsonPath("$.errors.quantity").value("MSG15"));
        place(trader, limit("BUY", "0.004", "1", null))
                .andExpect(jsonPath("$.errors.price").value("MSG15"));

        assertThat(orderCount(trader)).isZero();
    }

    @Test
    void MSG41_anUnknownPair_anUnopenedAccount_andAnotherTradersOrder_areNotFound() throws Exception {
        UUID trader = trader();
        UUID stranger = trader();
        UUID unopened = traderWithoutAccount();
        price("100");
        String id = id(place(trader, limit("BUY", "90", "1", null)).andExpect(status().isCreated()));

        place(
                        trader,
                        market("BUY", "\"quantity\": 1")
                                .replace(pair.toString(), UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        place(unopened, market("BUY", "\"quantity\": 1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(stranger, get(PAPER + "/orders/" + id), null).andExpect(status().isNotFound());
        as(stranger, post(PAPER + "/orders/" + id + "/cancel"), null).andExpect(status().isNotFound());
        as(stranger, get(PAPER + "/orders"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        as(unopened, get(PAPER + "/orders/open"), null).andExpect(status().isNotFound());
    }

    @Test
    void TR02_theOrderHistory_isFilteredByPairAndTime_andTheTradeHistoryByTime() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, limit("BUY", "90", "1", null)).andExpect(status().isCreated());
        place(trader, market("BUY", "\"quantity\": 1")).andExpect(status().isCreated());
        String future = Instant.now().plus(Duration.ofDays(1)).toString();

        as(trader, get(PAPER + "/orders").param("pairId", pair.toString()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].type").value("MARKET"));
        as(trader, get(PAPER + "/orders").param("pairId", UUID.randomUUID().toString()), null)
                .andExpect(jsonPath("$.items.length()").value(0));
        as(trader, get(PAPER + "/orders").param("from", future), null)
                .andExpect(jsonPath("$.items.length()").value(0));
        as(trader, get(PAPER + "/fills").param("from", future), null)
                .andExpect(jsonPath("$.items.length()").value(0));
        as(trader, get(PAPER + "/fills").param("pairId", UUID.randomUUID().toString()), null)
                .andExpect(jsonPath("$.items.length()").value(0));
        as(
                        trader,
                        get(PAPER + "/orders")
                                .param("from", future)
                                .param("to", Instant.now().toString()),
                        null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void TR02_tenTradersBuyingAtOnce_eachGetTheirOwnFill_andNoBalanceOrLedgerIsLost() throws Exception {
        price("100");
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buyers.add(trader());
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            // Five buy at the market, five rest a limit order below it, all released together.
            CountDownLatch start = new CountDownLatch(1);
            Map<UUID, Future<String>> placed = new HashMap<>();
            for (int i = 0; i < buyers.size(); i++) {
                UUID buyer = buyers.get(i);
                String body = i < 5 ? market("BUY", "\"quantity\": 2") : limit("BUY", "90", "1", null);
                placed.put(buyer, pool.submit(() -> {
                    start.await();
                    return id(place(buyer, body).andExpect(status().isCreated()));
                }));
            }
            start.countDown();
            Map<UUID, String> ids = new HashMap<>();
            for (Map.Entry<UUID, Future<String>> entry : placed.entrySet()) {
                ids.put(entry.getKey(), entry.getValue().get(30, TimeUnit.SECONDS));
            }

            Instant next = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
            worker.onMinuteKline(new MinuteKline(
                    MarketType.SPOT,
                    pair,
                    next,
                    new BigDecimal("89.5"),
                    new BigDecimal("95"),
                    true,
                    next.plusSeconds(59)));

            for (int i = 0; i < buyers.size(); i++) {
                UUID buyer = buyers.get(i);
                awaitStatus(buyer, ids.get(buyer), "FILLED");
                assertThat(locked(buyer, "USDT")).isZero();
                assertThat(spot(buyer, "USDT")).isEqualByComparingTo(i < 5 ? "9800" : "9910");
                assertThat(spot(buyer, base())).isEqualByComparingTo(i < 5 ? "1.998" : "0.999");
                assertLedgerAddsUpToTheBalances(buyer);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(sql.sql("select count(*) from paper_fill where pair_id = ?")
                        .param(pair)
                        .query(Long.class)
                        .single())
                .as("one fill per order, none twice")
                .isEqualTo(10);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private String market(String side, String size) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side + "\", \"type\": \"MARKET\", "
                + size + "}";
    }

    private String limit(String side, String price, String quantity, String timeInForce) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side + "\", \"type\": \"LIMIT\","
                + " \"price\": " + price + ", \"quantity\": " + quantity
                + (timeInForce == null ? "" : ", \"timeInForce\": \"" + timeInForce + "\"") + "}";
    }

    /** Streams a Spot ticker of the test's pair into the cache, newer than every earlier one. */
    private void price(String last) {
        Instant at = Instant.now().plusMillis(TICKS.incrementAndGet());
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
                        at));
        prices.flush();
    }

    private ResultActions place(UUID trader, String body) throws Exception {
        return as(trader, post(PAPER + "/orders"), body);
    }

    private static String id(ResultActions placed) throws Exception {
        return JsonPath.read(placed.andReturn().getResponse().getContentAsString(), "$.orderId");
    }

    private void awaitStatus(UUID trader, String id, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String status;
        do {
            status = sql.sql("select order_status from paper_order where order_id = ?")
                    .param(UUID.fromString(id))
                    .query(String.class)
                    .single();
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertThat(status).as("order %s of %s", id, trader).isEqualTo(expected);
    }

    private Instant watermark() {
        return matching.watermark(MarketType.SPOT, pair).orElse(null);
    }

    private String base() {
        return symbol.replace("USDT", "");
    }

    private UUID accountOf(UUID trader) {
        return sql.sql("select account_id from paper_account where user_id = ?")
                .param(trader)
                .query(UUID.class)
                .single();
    }

    private long orderCount(UUID trader) {
        return sql.sql("select count(*) from paper_order o join paper_account a on a.account_id = o.account_id"
                        + " where a.user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private BigDecimal spot(UUID trader, String coin) {
        return balance(trader, coin).getOrDefault("total", BigDecimal.ZERO);
    }

    private BigDecimal locked(UUID trader, String coin) {
        return balance(trader, coin).getOrDefault("locked", BigDecimal.ZERO);
    }

    private Map<String, BigDecimal> balance(UUID trader, String coin) {
        Map<String, BigDecimal> found = new HashMap<>();
        sql.sql("""
                        select b.free_amount + b.locked_amount as total, b.locked_amount as locked
                          from paper_balance b
                          join paper_account a on a.account_id = b.account_id
                          join coin c on c.coin_id = b.coin_id
                         where a.user_id = ? and b.wallet_type = 'SPOT' and c.symbol = ?""").params(trader, coin).query().listOfRows().forEach(row -> {
            found.put("total", (BigDecimal) row.get("total"));
            found.put("locked", (BigDecimal) row.get("locked"));
        });
        return found;
    }

    private void assertLedgerAddsUpToTheBalances(UUID trader) {
        List<Map<String, Object>> drift = sql.sql("""
                        select b.wallet_type, b.coin_id, b.free_amount + b.locked_amount as total,
                               coalesce(sum(e.amount), 0) as entries
                          from paper_balance b
                          join paper_account a on a.account_id = b.account_id
                          left join paper_ledger_entry e
                            on e.account_id = b.account_id and e.wallet_type = b.wallet_type
                           and e.coin_id = b.coin_id
                         where a.user_id = ?
                         group by b.wallet_type, b.coin_id, b.free_amount, b.locked_amount
                        having b.free_amount + b.locked_amount <> coalesce(sum(e.amount), 0)""").param(trader).query().listOfRows();
        assertThat(drift).as("balances whose ledger does not add up to them").isEmpty();
    }

    /** A Trader with an opened paper account. */
    private UUID trader() throws Exception {
        UUID id = traderWithoutAccount();
        as(id, post(PAPER + "/account"), null).andExpect(status().isCreated());
        return id;
    }

    private UUID traderWithoutAccount() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@tr02.invalid", now, now).update();
        traders.add(id);
        return id;
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
