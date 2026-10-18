package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.BookTop;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.impl.TradingMarketData;
import com.cryptopilot.paper.PaperLedgerInvariant;
import com.cryptopilot.paper.job.PaperMatchingWorker;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import com.cryptopilot.paper.service.PaperMatchingService;
import com.cryptopilot.paper.service.PaperPnlService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.test.web.servlet.MvcResult;
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
    private TradingMarketData trading;

    @Autowired
    private PaperMatchingService matching;

    @Autowired
    private PaperPnlService pnl;

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
            for (String table : List.of(
                    "paper_fill",
                    "paper_order",
                    "paper_order_list",
                    "paper_ledger_entry",
                    "paper_transfer",
                    "paper_balance",
                    "paper_equity_snapshot")) {
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
    void TR02_anIocOrFokLimitThePriceHasNotReached_expiresAtOnce_andGtxIsNotASpotTimeInForce() throws Exception {
        UUID trader = trader();
        price("100");

        place(trader, limit("BUY", "90", "1", "IOC"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("NOT_FILLABLE_ON_ARRIVAL"));
        place(trader, limit("BUY", "90", "1", "FOK"))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        // GTX is the post-only of Futures; on Spot the post-only order is a LIMIT_MAKER.
        place(trader, limit("BUY", "110", "1", "GTX"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.timeInForce").value("MSG15"));

        assertThat(spot(trader, "USDT")).isEqualByComparingTo("10000");
        assertThat(locked(trader, "USDT")).isZero();
        as(trader, get(PAPER + "/orders").param("status", "EXPIRED"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    /**
     * TR-12: a LIMIT_MAKER order that would take the book on arrival is refused and leaves nothing; one below the ask
     * waits, locks its quote, is a resting order of the engine, and fills as a maker.
     */
    @Test
    void TR12_aLimitMaker_thatWouldTake_isRefused_andOneThatWaits_fillsAsAMaker() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("100", "1")), List.of(level("99", "1")));

        place(trader, limitMaker("BUY", "100", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_WOULD_TAKE"));
        assertThat(locked(trader, "USDT")).isZero();
        as(trader, get(PAPER + "/orders"), null)
                .andExpect(jsonPath("$.items.length()").value(0));

        String id = id(place(trader, limitMaker("BUY", "99", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("LIMIT_MAKER"))
                .andExpect(jsonPath("$.timeInForce").doesNotExist())
                .andExpect(jsonPath("$.status").value("NEW")));
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("99");
        RestingOrder resting = matching.restingOrders().stream()
                .filter(order -> order.orderId().toString().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the LIMIT_MAKER order is not a resting order"));
        assertThat(matching.fill(resting, Instant.now(), FillSource.LIVE)).isTrue();
        as(trader, get(PAPER + "/fills"), null)
                .andExpect(jsonPath("$.items[0].orderId").value(id))
                .andExpect(jsonPath("$.items[0].liquidity").value("MAKER"));
        assertThat(locked(trader, "USDT")).isZero();
    }

    /** TR-12: a LIMIT_MAKER order has a price and a quantity and no time in force. */
    @Test
    void TR12_aLimitMaker_withATimeInForce_orWithoutAPrice_isMsg15OrMsg01() throws Exception {
        UUID trader = trader();
        price("100");

        place(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"BUY\","
                                + " \"type\": \"LIMIT_MAKER\", \"price\": 90, \"quantity\": 1, \"timeInForce\": \"GTC\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.timeInForce").value("MSG15"));
        place(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"BUY\","
                                + " \"type\": \"LIMIT_MAKER\", \"quantity\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG01"));
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
    // Spot stops (TR-09, TR-11): STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, TAKE_PROFIT_LIMIT
    // ------------------------------------------------------------------------------------------

    /**
     * TR-09: a STOP_LOSS_LIMIT buy above the last price waits, locking what its limit needs; a trade at the stop
     * triggers it and, live, its limit meets the book at once as a taker.
     */
    @Test
    void TR09_aStopLossLimitBuy_isTriggeredByATradeAtTheStop_andTakesTheBook() throws Exception {
        UUID trader = trader();
        price("100");

        String id = id(place(trader, stop("STOP_LOSS_LIMIT", "BUY", "105", null, "106", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("STOP_LOSS_LIMIT"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.stopPrice").value(105.0))
                .andExpect(jsonPath("$.timeInForce").value("GTC"))
                .andExpect(jsonPath("$.triggeredAt").doesNotExist()));
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("106");

        depth(List.of(level("105.5", "5")), List.of(level("104", "5")));
        trade("104.99", 1);
        trade("105", 2);

        awaitStatus(trader, id, "FILLED");
        as(trader, get(PAPER + "/orders/" + id), null)
                .andExpect(jsonPath("$.avgPrice").value(105.5))
                .andExpect(jsonPath("$.triggeredAt").exists());
        as(trader, get(PAPER + "/fills").param("pairId", pair.toString()), null)
                .andExpect(jsonPath("$.items[0].liquidity").value("TAKER"));
        assertThat(locked(trader, "USDT")).isZero();
        assertLedgerAddsUpToTheBalances(trader);
    }

    /**
     * TR-09: a STOP_LOSS sell below the last price locks its quantity; triggered, it executes as a MARKET order at the
     * bids, as the exchange's STOP_LOSS does.
     */
    @Test
    void TR09_aStopLossSell_executesAsAMarketOrder_onceTriggered() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());

        String id = id(place(trader, stop("STOP_LOSS", "SELL", "95", null, null, "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("STOP_LOSS"))
                .andExpect(jsonPath("$.price").doesNotExist())
                .andExpect(jsonPath("$.timeInForce").doesNotExist()));
        assertThat(locked(trader, base())).isEqualByComparingTo("1");

        depth(List.of(level("96", "5")), List.of(level("94.5", "5")));
        trade("95", 1);

        awaitStatus(trader, id, "FILLED");
        as(trader, get(PAPER + "/orders/" + id), null)
                .andExpect(jsonPath("$.avgPrice").value(94.5));
        assertThat(locked(trader, base())).isZero();
        assertLedgerAddsUpToTheBalances(trader);
    }

    /**
     * TR-09: the side of the price a stop waits on is fixed by its type and side, as the exchange fixes it; a stop whose
     * condition the last price already meets would trigger immediately and is refused; without a price it cannot be
     * placed.
     */
    @Test
    void TR09_theStopConditionFollowsTheType_andAStopAlreadyMet_isRefused() throws Exception {
        UUID trader = trader();

        place(trader, stop("STOP_LOSS_LIMIT", "BUY", "105", null, "106", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_PRICE_UNAVAILABLE"));
        price("100");
        // A STOP_LOSS sell waits below the price: a stop at 101 is already met.
        place(trader, stop("STOP_LOSS_LIMIT", "SELL", "101", null, "99", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_WOULD_TRIGGER"));
        // A TAKE_PROFIT buy waits below the price too, a TAKE_PROFIT sell above it.
        place(trader, stop("TAKE_PROFIT_LIMIT", "BUY", "100", null, "99", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_WOULD_TRIGGER"));
        String takeProfit = id(place(trader, stop("TAKE_PROFIT_LIMIT", "BUY", "95", null, "96", "1"))
                .andExpect(status().isCreated()));

        assertThat(matching.triggerOrders())
                .filteredOn(order -> order.orderId().toString().equals(takeProfit))
                .singleElement()
                .satisfies(order -> assertThat(order.condition()).isEqualTo(TriggerCondition.AT_OR_BELOW));
    }

    /** TR-09: a cancelled stop gives back what it locked, and a trigger that comes after the cancel does nothing. */
    @Test
    void TR09_aCancelledStop_unlocks_andALateTriggerDoesNothing() throws Exception {
        UUID trader = trader();
        price("100");
        String id = id(place(trader, stop("STOP_LOSS_LIMIT", "BUY", "105", null, "106", "1"))
                .andExpect(status().isCreated()));
        TriggerOrder watched = matching.triggerOrders().stream()
                .filter(order -> order.orderId().toString().equals(id))
                .findFirst()
                .orElseThrow();
        assertThat(watched.condition()).isEqualTo(TriggerCondition.AT_OR_ABOVE);

        as(trader, post(PAPER + "/orders/" + id + "/cancel"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        assertThat(locked(trader, "USDT")).isZero();
        assertThat(matching.trigger(watched, Instant.now(), watched.stopPrice(), FillSource.LIVE, null))
                .isFalse();
        assertThat(matching.triggerOrders())
                .noneMatch(order -> order.orderId().toString().equals(id));
    }

    /** TR-09: replayed, a triggered limit stop waits at its limit price from the trigger on. */
    @Test
    void TR09_aStopTriggeredByAReplay_waitsAtItsPrice_fromTheTrigger() throws Exception {
        UUID trader = trader();
        price("100");
        String id = id(place(trader, stop("STOP_LOSS_LIMIT", "BUY", "105", null, "104", "1"))
                .andExpect(status().isCreated()));
        TriggerOrder watched = matching.triggerOrders().stream()
                .filter(order -> order.orderId().toString().equals(id))
                .findFirst()
                .orElseThrow();
        Instant at = Instant.now().plusSeconds(1);

        assertThat(matching.trigger(watched, at, watched.stopPrice(), FillSource.REPLAY, null))
                .isTrue();

        as(trader, get(PAPER + "/orders/" + id), null)
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.triggeredAt").exists());
        assertThat(matching.restingOrders())
                .filteredOn(order -> order.orderId().toString().equals(id))
                .singleElement()
                .satisfies(order -> assertThat(order.placedAt()).isCloseTo(at, within(1, ChronoUnit.MILLIS)));
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("104");
    }

    /**
     * TR-11: a STOP_LOSS_LIMIT sell with a trailingDelta and no stopPrice tracks from the next trade, follows the high,
     * and triggers when the price falls back by the delta; its limit then takes the bids.
     */
    @Test
    void TR11_aTrailingStopLossSell_followsTheHigh_andTriggersOnTheDelta() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());

        String id = id(place(trader, stop("STOP_LOSS_LIMIT", "SELL", null, "200", "90", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("STOP_LOSS_LIMIT"))
                .andExpect(jsonPath("$.trailingDelta").value(200))
                .andExpect(jsonPath("$.stopPrice").doesNotExist()));
        assertThat(locked(trader, base())).isEqualByComparingTo("1");

        depth(List.of(level("108", "5")), List.of(level("107", "5")));
        trade("110", 1);
        trade("108", 2);
        // 2 % back from the high of 110: 107.8.
        trade("107.8", 3);

        awaitStatus(trader, id, "FILLED");
        as(trader, get(PAPER + "/orders/" + id), null)
                .andExpect(jsonPath("$.avgPrice").value(107.0));
        assertThat(locked(trader, base())).isZero();
        assertLedgerAddsUpToTheBalances(trader);
    }

    /**
     * TR-11: with a stopPrice, a trailing TAKE_PROFIT_LIMIT buy starts tracking only once its stop condition is met;
     * the best price the engine stores is where it resumes after a restart.
     */
    @Test
    void TR11_aTrailingStopWithAStopPrice_waitsForIt_andResumesFromItsStoredBestPrice() throws Exception {
        UUID trader = trader();
        price("100");

        String id = id(place(trader, stop("TAKE_PROFIT_LIMIT", "BUY", "90", "100", "95", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stopPrice").value(90.0))
                .andExpect(jsonPath("$.trailingDelta").value(100)));
        TriggerOrder watched = matching.triggerOrders().stream()
                .filter(order -> order.orderId().toString().equals(id))
                .findFirst()
                .orElseThrow();
        assertThat(watched.condition()).isEqualTo(TriggerCondition.AT_OR_BELOW);
        assertThat(watched.extreme()).isNull();

        matching.saveExtremes(Map.of(UUID.fromString(id), new BigDecimal("88")));

        assertThat(matching.triggerOrders())
                .filteredOn(order -> order.orderId().toString().equals(id))
                .singleElement()
                .satisfies(order -> assertThat(order.extreme()).isEqualByComparingTo("88"));
    }

    /** TR-09, TR-11: each kind takes its own fields only, and the delta stays within 10 to 2000 basis points. */
    @Test
    void TR09_TR11_theStopFields_belongToTheStopsOnly() throws Exception {
        UUID trader = trader();
        price("100");

        place(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"BUY\", \"type\": \"LIMIT\","
                                + " \"price\": 90, \"quantity\": 1, \"stopPrice\": 95, \"trailingDelta\": 100}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stopPrice").value("MSG15"))
                .andExpect(jsonPath("$.errors.trailingDelta").value("MSG15"));
        place(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"BUY\","
                                + " \"type\": \"STOP_LOSS_LIMIT\", \"price\": 90, \"quantity\": 1,"
                                + " \"timeInForce\": \"GTX\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stopPrice").value("MSG01"))
                .andExpect(jsonPath("$.errors.timeInForce").value("MSG15"));
        place(trader, stop("STOP_LOSS", "SELL", "95", null, "94", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG15"));
        place(trader, stop("STOP_LOSS", "BUY", null, "100", null, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.stopPrice").value("MSG01"));
        place(trader, stop("STOP_LOSS_LIMIT", "SELL", null, "2500", "90", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.trailingDelta").value("MSG15"));
    }

    /** A MARKET sell by quoteOrderQty sells what brings in that amount, as the exchange's quoteOrderQty on a SELL. */
    @Test
    void TR06_aMarketSellByQuoteOrderQty_sellsWhatBringsInTheAmount() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());

        place(trader, market("SELL", "\"quoteOrderQty\": 50"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.quoteOrderQty").value(50.0))
                .andExpect(jsonPath("$.executedQuantity").value(0.5))
                .andExpect(jsonPath("$.cumQuote").value(50.0));
        assertLedgerAddsUpToTheBalances(trader);
    }

    // ------------------------------------------------------------------------------------------
    // Groups of orders (TR-10)
    // ------------------------------------------------------------------------------------------

    /** TR-10: a sell OCO locks its quantity once; the take profit filling expires the stop and gives the lock back. */
    @Test
    void TR10_aSellOco_locksOnce_andItsTakeProfitFilling_expiresTheStop() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());

        String body = placeList(trader, oco("SELL", "1", "110", "95", "94"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("OCO"))
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].type").value("LIMIT_MAKER"))
                .andExpect(jsonPath("$.orders[0].status").value("NEW"))
                .andExpect(jsonPath("$.orders[1].type").value("STOP_LOSS_LIMIT"))
                .andExpect(jsonPath("$.orders[1].status").value("NEW"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String listId = JsonPath.read(body, "$.listId");
        String takeProfit = JsonPath.read(body, "$.orders[0].orderId");
        String stop = JsonPath.read(body, "$.orders[1].orderId");
        assertThat(locked(trader, base())).isEqualByComparingTo("1");

        trade("110.01", 1);

        awaitStatus(trader, takeProfit, "FILLED");
        as(trader, get(PAPER + "/orders/" + stop), null)
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("OCO_OTHER_EXECUTED"));
        as(trader, get(PAPER + "/order-lists/" + listId), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALL_DONE"));
        assertThat(locked(trader, base())).isZero();
        assertThat(matching.triggerOrders())
                .noneMatch(order -> order.orderId().toString().equals(stop));
        assertLedgerAddsUpToTheBalances(trader);
    }

    /** TR-10: the stop of an OCO triggering expires the take profit; its limit then takes the bids. */
    @Test
    void TR10_theStopOfAnOcoTriggering_expiresTheTakeProfit() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());
        String body = placeList(trader, oco("SELL", "1", "110", "95", "94"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String takeProfit = JsonPath.read(body, "$.orders[0].orderId");
        String stop = JsonPath.read(body, "$.orders[1].orderId");

        depth(List.of(level("95.5", "5")), List.of(level("94.5", "5")));
        trade("95", 1);

        awaitStatus(trader, stop, "FILLED");
        as(trader, get(PAPER + "/orders/" + stop), null)
                .andExpect(jsonPath("$.avgPrice").value(94.5));
        as(trader, get(PAPER + "/orders/" + takeProfit), null)
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("OCO_OTHER_EXECUTED"));
        assertThat(locked(trader, base())).isZero();
        assertLedgerAddsUpToTheBalances(trader);
    }

    /**
     * TR-10: a buy OCO locks the larger of what its two orders need; cancelling one of its orders cancels the group
     * and gives the lock back once.
     */
    @Test
    void TR10_aBuyOco_locksTheLargerNeed_andCancellingOneOrder_cancelsTheGroup() throws Exception {
        UUID trader = trader();
        price("100");
        String body = placeList(trader, oco("BUY", "1", "90", "105", "106"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String listId = JsonPath.read(body, "$.listId");
        String takeProfit = JsonPath.read(body, "$.orders[0].orderId");
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("106");

        as(trader, post(PAPER + "/orders/" + takeProfit + "/cancel"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        as(trader, get(PAPER + "/order-lists/" + listId), null)
                .andExpect(jsonPath("$.status").value("ALL_DONE"))
                .andExpect(jsonPath("$.orders[*].status", everyItem(is("CANCELED"))));
        assertThat(locked(trader, "USDT")).isZero();
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("10000");
        as(trader, post(PAPER + "/order-lists/" + listId + "/cancel"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_NOT_OPEN"));
    }

    /** TR-10: an OCO's take profit and stop must lie on either side of the last price. */
    @Test
    void TR10_anOcoWhosePricesDoNotLieAroundTheLastPrice_isMsg15() throws Exception {
        UUID trader = trader();
        price("100");

        placeList(trader, oco("SELL", "1", "99", "101", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['oco.takeProfitPrice']").value("MSG15"))
                .andExpect(jsonPath("$.errors['oco.stopPrice']").value("MSG15"));
        as(trader, get(PAPER + "/orders"), null)
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    /**
     * TR-10: an OTOCO's OCO waits, locking nothing, while its working buy waits; the buy filling places the OCO, which
     * locks the coin bought.
     */
    @Test
    void TR10_anOtoco_placesItsOco_onceTheWorkingOrderFills() throws Exception {
        UUID trader = trader();
        price("100");
        String body = placeList(trader, otoco("99", "1", "0.99", "110", "95", "94"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("OTOCO"))
                .andExpect(jsonPath("$.orders[0].status").value("NEW"))
                .andExpect(jsonPath("$.orders[1].status").value("PENDING_NEW"))
                .andExpect(jsonPath("$.orders[2].status").value("PENDING_NEW"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String listId = JsonPath.read(body, "$.listId");
        String working = JsonPath.read(body, "$.orders[0].orderId");
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("99");
        as(trader, get(PAPER + "/orders/open"), null)
                .andExpect(jsonPath("$.length()").value(3));

        trade("98.99", 1);

        awaitStatus(trader, working, "FILLED");
        as(trader, get(PAPER + "/order-lists/" + listId), null)
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.orders[1].status").value("NEW"))
                .andExpect(jsonPath("$.orders[2].status").value("NEW"));
        assertThat(locked(trader, "USDT")).isZero();
        assertThat(locked(trader, base())).isEqualByComparingTo("0.99");
        assertLedgerAddsUpToTheBalances(trader);
    }

    /**
     * TR-10: an OTOCO's OCO that needs more than the buy left free — the fee was taken in the coin bought — is
     * REJECTED when the buy fills, and the buy's fill stands.
     */
    @Test
    void TR10_anOtocoWhoseOcoNeedsMoreThanIsFree_isRejected_andTheFillStands() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("99", "5")), List.of(level("98", "5")));

        String body = placeList(trader, otoco("100", "1", "1", "110", "95", "94"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ALL_DONE"))
                .andExpect(jsonPath("$.orders[0].status").value("FILLED"))
                .andExpect(jsonPath("$.orders[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.orders[1].statusReason").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.orders[2].status").value("REJECTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<Object>read(body, "$.orders[0].avgPrice")).isNotNull();
        assertThat(spot(trader, base())).isEqualByComparingTo("0.999");
        assertThat(locked(trader, base())).isZero();
    }

    /** TR-10: an OTO whose working order fills on arrival places its pending order at once. */
    @Test
    void TR10_anOtoWhoseWorkingOrderFillsOnArrival_placesThePendingOrderAtOnce() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("100", "5")), List.of(level("99", "5")));

        placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OTO\","
                                + " \"working\": {\"side\": \"BUY\", \"type\": \"LIMIT\", \"price\": 101, \"quantity\": 1},"
                                + " \"pending\": {\"side\": \"SELL\", \"type\": \"LIMIT\", \"price\": 120, \"quantity\": 0.99}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders[0].status").value("FILLED"))
                .andExpect(jsonPath("$.orders[0].avgPrice").value(100.0))
                .andExpect(jsonPath("$.orders[1].status").value("NEW"))
                .andExpect(jsonPath("$.status").value("EXECUTING"));

        assertThat(locked(trader, base())).isEqualByComparingTo("0.99");
        assertLedgerAddsUpToTheBalances(trader);
    }

    /**
     * TR-10: without a stop limit price, the stop of an OCO is a STOP_LOSS, executed as a MARKET order once triggered;
     * its trigger expires the take profit.
     */
    @Test
    void TR10_anOcoWithoutAStopLimitPrice_stopsWithAStopLoss_executedAtTheMarket() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());
        String body = placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OCO\","
                                + " \"oco\": {\"side\": \"SELL\", \"quantity\": 1, \"takeProfitPrice\": 110,"
                                + " \"stopPrice\": 95}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders[1].type").value("STOP_LOSS"))
                .andExpect(jsonPath("$.orders[1].price").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String takeProfit = JsonPath.read(body, "$.orders[0].orderId");
        String stop = JsonPath.read(body, "$.orders[1].orderId");

        depth(List.of(level("96", "5")), List.of(level("94", "5")));
        trade("95", 1);

        awaitStatus(trader, stop, "FILLED");
        as(trader, get(PAPER + "/orders/" + stop), null)
                .andExpect(jsonPath("$.avgPrice").value(94.0));
        as(trader, get(PAPER + "/orders/" + takeProfit), null)
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("OCO_OTHER_EXECUTED"));
        assertThat(locked(trader, base())).isZero();
        assertLedgerAddsUpToTheBalances(trader);
    }

    /** TR-10: an OCO whose take profit the book already reaches is refused, as the exchange refuses its LIMIT_MAKER. */
    @Test
    void TR10_anOcoWhoseTakeProfitWouldTake_isRefused() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());
        depth(List.of(level("112", "5")), List.of(level("111", "5")));

        placeList(trader, oco("SELL", "1", "110", "95", "94"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_WOULD_TAKE"));
        assertThat(locked(trader, base())).isZero();
    }

    /**
     * TR-10: an OTO's pending order may be a Spot stop; placed once the working buy fills, it locks the coin bought and
     * waits for its trigger.
     */
    @Test
    void TR10_anOtoWithAPendingStopLoss_waitsForItsTrigger_onceTheWorkingOrderFills() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("100", "5")), List.of(level("99", "5")));

        String body = placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OTO\","
                                + " \"working\": {\"side\": \"BUY\", \"type\": \"LIMIT\", \"price\": 101, \"quantity\": 1},"
                                + " \"pending\": {\"side\": \"SELL\", \"type\": \"STOP_LOSS\", \"stopPrice\": 95,"
                                + " \"quantity\": 0.99}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders[0].status").value("FILLED"))
                .andExpect(jsonPath("$.orders[1].type").value("STOP_LOSS"))
                .andExpect(jsonPath("$.orders[1].status").value("NEW"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String pending = JsonPath.read(body, "$.orders[1].orderId");

        assertThat(locked(trader, base())).isEqualByComparingTo("0.99");
        assertThat(matching.triggerOrders())
                .filteredOn(order -> order.orderId().toString().equals(pending))
                .singleElement()
                .satisfies(order -> assertThat(order.condition()).isEqualTo(TriggerCondition.AT_OR_BELOW));
    }

    /** TR-10: each order of a group takes the fields of its type, and the prices of a pending OCO lie in order. */
    @Test
    void TR10_theOrdersOfAGroup_takeTheFieldsOfTheirTypes() throws Exception {
        UUID trader = trader();
        price("100");

        placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OTO\","
                                + " \"working\": {\"side\": \"BUY\", \"type\": \"LIMIT\", \"price\": 99, \"quantity\": 1},"
                                + " \"pending\": {\"side\": \"SELL\", \"type\": \"STOP_LOSS\", \"price\": 94, \"quantity\": 1}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['pending.stopPrice']").value("MSG01"))
                .andExpect(jsonPath("$.errors['pending.price']").value("MSG15"));
        placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OTO\","
                                + " \"working\": {\"side\": \"BUY\", \"type\": \"LIMIT\", \"stopPrice\": 98, \"quantity\": 1},"
                                + " \"pending\": {\"side\": \"SELL\", \"type\": \"LIMIT\", \"price\": 120, \"quantity\": 1}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['working.price']").value("MSG01"))
                .andExpect(jsonPath("$.errors['working.stopPrice']").value("MSG15"));
        placeList(trader, otoco("99", "1", "1", "94", "95", "93"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['oco.takeProfitPrice']").value("MSG15"));
        placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OTO\","
                                + " \"working\": {\"side\": \"BUY\", \"type\": \"LIMIT\", \"price\": 99, \"quantity\": 1},"
                                + " \"pending\": {\"side\": \"SELL\", \"type\": \"STOP_LOSS\", \"stopPrice\": 101,"
                                + " \"quantity\": 1}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_WOULD_TRIGGER"));
        as(trader, get(PAPER + "/orders"), null)
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    /** TR-10: each kind of group takes its own parts only. */
    @Test
    void TR10_eachKindOfGroup_takesItsOwnPartsOnly() throws Exception {
        UUID trader = trader();
        price("100");

        placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OCO\","
                                + " \"working\": {\"side\": \"BUY\", \"type\": \"LIMIT\", \"price\": 99, \"quantity\": 1}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.oco").value("MSG01"))
                .andExpect(jsonPath("$.errors.working").value("MSG15"));
        placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OTO\","
                                + " \"working\": {\"side\": \"BUY\", \"type\": \"STOP_LOSS_LIMIT\", \"price\": 99, \"quantity\": 1}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pending").value("MSG01"))
                .andExpect(jsonPath("$.errors['working.type']").value("MSG15"));
    }

    // ------------------------------------------------------------------------------------------
    // Cost and PnL (TR-13)
    // ------------------------------------------------------------------------------------------

    /**
     * TR-13: a buy's cost is what it paid, the fee taken in the coin included; a sale takes away its share of the cost,
     * so what is left keeps its average cost; the PnL is the value at the last price less that cost.
     */
    @Test
    void TR13_aBuyAndASale_setTheCost_andThePnlIsTheValueLessIt() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());
        price("110");
        place(trader, market("SELL", "\"quantity\": 1")).andExpect(status().isCreated());

        // 2 bought for 200, 0.002 of it the fee: 1.998 held. 1 sold: 0.998 left, which keeps 0.998 / 1.998 of 200.
        String body = as(trader, get(PAPER + "/spot/pnl"), null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(body, "$.coins[?(@.asset == '" + base() + "')]");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(new BigDecimal(String.valueOf(row.get("quantity")))).isEqualByComparingTo("0.998");
        assertThat(new BigDecimal(String.valueOf(row.get("costBasis")))).isEqualByComparingTo("99.8998999");
        assertThat(new BigDecimal(String.valueOf(row.get("averageCost"))))
                .isCloseTo(new BigDecimal("100.1001001"), within(new BigDecimal("0.0000001")));
        assertThat(new BigDecimal(String.valueOf(row.get("value")))).isEqualByComparingTo("109.78");
        assertThat(new BigDecimal(String.valueOf(row.get("unrealizedPnl")))).isEqualByComparingTo("9.8801001");
        assertThat(new BigDecimal(String.valueOf(row.get("unrealizedPnlPercent"))))
                .isEqualByComparingTo("9.89");
        // No price of the granted BTC, ETH and BNB was known at the opening: their cost is not known.
        assertThat(JsonPath.<Boolean>read(body, "$.complete")).isFalse();
    }

    /**
     * TR-13: the account's worth is snapshotted when it opens; today's PnL is the worth now less that snapshot, and a
     * second snapshot of the same day is never taken.
     */
    @Test
    void TR13_todaysPnl_isTheWorthNowLessTheSnapshotOfTheDay() throws Exception {
        UUID trader = trader();
        price("100");
        place(trader, market("BUY", "\"quantity\": 2")).andExpect(status().isCreated());

        // Opened with 10,000 USDT in Spot and 5,000 in Futures, the other coins without a price: 15,000.
        // Now 9,800 USDT and 1.998 of the coin at 100: 14,999.8, the fee of 0.002 × 100 less. The granted BTC, ETH
        // and BNB have no price, so no snapshot of the day is taken and today's PnL is not known (PT-R02).
        as(trader, get(PAPER + "/equity").param("days", "7"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEquity").value(14999.8))
                .andExpect(jsonPath("$.todayPnl").value(nullValue()))
                .andExpect(jsonPath("$.history.length()").value(0));

        pnl.snapshotAll();

        assertThat(sql.sql("""
                        select count(*) from paper_equity_snapshot s join paper_account a on a.account_id = s.account_id
                         where a.user_id = ?""").param(trader).query(Long.class).single()).isZero();
        as(trader, get(PAPER + "/equity").param("days", "0"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.days").value("MSG01"));
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
                MarketType.SPOT,
                pair,
                next,
                new BigDecimal("89.5"),
                new BigDecimal("95"),
                new BigDecimal("92"),
                true,
                next.plusSeconds(59)));

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
    // Taking the book (TR-06, Q-T7)
    // ------------------------------------------------------------------------------------------

    @Test
    void QT7_aMarketBuy_walksTheAsksOfTheDepth_oneFillPerLevel_atTheirVwap() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("100", "1"), level("101", "2")), List.of(level("99", "1")));

        place(trader, market("BUY", "\"quantity\": 2.5"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.statusReason").doesNotExist())
                .andExpect(jsonPath("$.executedQuantity").value(2.5))
                .andExpect(jsonPath("$.cumQuote").value(251.5))
                .andExpect(jsonPath("$.avgPrice").value(100.6));

        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9748.5");
        assertThat(spot(trader, base())).as("2.5 less 0.001 and 0.0015 of fees").isEqualByComparingTo("2.4975");
        as(trader, get(PAPER + "/fills"), null)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].liquidity").value(everyItem(is("TAKER"))));
        assertThat(sql.sql("select fill_price from paper_fill where pair_id = ? order by fill_price")
                        .param(pair)
                        .query(BigDecimal.class)
                        .list())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("100"), new BigDecimal("101"));
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void QT7_aMarketSellLargerThanTheDepth_takesTheRestAtTheLastBid_andIsFlagged() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("100", "10")), List.of(level("99", "1"), level("98", "1")));
        place(trader, market("BUY", "\"quantity\": 3")).andExpect(status().isCreated());

        place(trader, market("SELL", "\"quantity\": 2.5"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.statusReason").value("DEPTH_EXHAUSTED"))
                .andExpect(jsonPath("$.cumQuote").value(246.0))
                .andExpect(jsonPath("$.avgPrice").value(98.4));

        assertThat(spot(trader, base())).isEqualByComparingTo("0.497");
        assertThat(spot(trader, "USDT")).as("10000 - 300 + 246 - 0.246 fee").isEqualByComparingTo("9945.754");
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void QT7_aMarketBuyByTotal_spendsLevelByLevel_onTheStep() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("100", "1"), level("101", "2")), List.of(level("99", "1")));

        place(trader, market("BUY", "\"quoteOrderQty\": 200"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.executedQuantity").value(1.99))
                .andExpect(jsonPath("$.cumQuote").value(199.99));

        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9800.01");
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void QT7_withoutDepth_aMarketOrderExecutesAtTheBestAsk_notTheLastPrice() throws Exception {
        UUID trader = trader();
        price("100");
        trading.recordBookTop(new BookTop(
                MarketType.SPOT,
                pair,
                TICKS.incrementAndGet(),
                new BigDecimal("100.4"),
                BigDecimal.ONE,
                new BigDecimal("100.5"),
                BigDecimal.TEN,
                Instant.now()));

        place(trader, market("BUY", "\"quantity\": 1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.avgPrice").value(100.5))
                .andExpect(jsonPath("$.statusReason").doesNotExist());
    }

    @Test
    void TR06_aLimitBuyCrossingTheBook_takesTheAsksUpToItsPrice_andTheRestWaits_untilFilledAsAMaker() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("100", "1"), level("102", "5")), List.of(level("99", "1")));

        String id = id(place(trader, limit("BUY", "101", "3", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.executedQuantity").value(1.0))
                .andExpect(jsonPath("$.avgPrice").value(100.0))
                .andExpect(jsonPath("$.closedAt").doesNotExist()));
        assertThat(locked(trader, "USDT")).as("101 x the 2 left").isEqualByComparingTo("202");
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9900");
        as(trader, get(PAPER + "/orders/open"), null)
                .andExpect(jsonPath("$[0].orderId").value(id));

        RestingOrder resting = new RestingOrder(
                UUID.fromString(id),
                accountOf(trader),
                MarketType.SPOT,
                pair,
                OrderSide.BUY,
                new BigDecimal("101"),
                Instant.now());
        assertThat(matching.fill(resting, Instant.now().plusSeconds(1), FillSource.LIVE))
                .isTrue();

        as(trader, get(PAPER + "/orders/" + id), null)
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.executedQuantity").value(3.0))
                .andExpect(jsonPath("$.cumQuote").value(302.0));
        assertThat(locked(trader, "USDT")).isZero();
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9698");
        assertThat(spot(trader, base()))
                .as("3 less 0.001 taker and 0.002 maker fees")
                .isEqualByComparingTo("2.997");
        as(trader, get(PAPER + "/fills"), null)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].liquidity").value("MAKER"))
                .andExpect(jsonPath("$.items[1].liquidity").value("TAKER"));
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void TR06_crossingTheBook_anIocKeepsWhatItTook_aFokTakesNothing_andALimitMakerIsRefused() throws Exception {
        UUID trader = trader();
        price("100");
        depth(List.of(level("100", "1"), level("102", "5")), List.of(level("99", "1")));

        place(trader, limit("BUY", "101", "3", "IOC"))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("NOT_FILLABLE_ON_ARRIVAL"))
                .andExpect(jsonPath("$.executedQuantity").value(1.0));
        place(trader, limit("BUY", "101", "3", "FOK"))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.executedQuantity").value(0.0));
        place(trader, limit("BUY", "102", "3", "FOK"))
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.cumQuote").value(304.0));
        place(trader, limitMaker("BUY", "100", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_WOULD_TAKE"));
        place(trader, limitMaker("BUY", "99.99", "1"))
                .andExpect(jsonPath("$.status").value("NEW"));

        assertThat(locked(trader, "USDT")).as("only the post-only that waits").isEqualByComparingTo("99.99");
        assertThat(spot(trader, "USDT")).isEqualByComparingTo("9596");
        assertLedgerAddsUpToTheBalances(trader);
    }

    @Test
    void TR06_cancelAll_cancelsEveryWorkingOrderOfThePair_andReleasesWhatTheyLocked() throws Exception {
        UUID trader = trader();
        price("100");
        String first = id(place(trader, limit("BUY", "90", "1", null)).andExpect(status().isCreated()));
        String second = id(place(trader, limit("BUY", "80", "2", null)).andExpect(status().isCreated()));
        place(trader, market("BUY", "\"quantity\": 1")).andExpect(status().isCreated());
        String sell = id(place(trader, limit("SELL", "120", "0.5", null)).andExpect(status().isCreated()));
        assertThat(locked(trader, "USDT")).isEqualByComparingTo("250");

        as(trader, delete(PAPER + "/orders/open").param("pairId", pair.toString()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].orderId").value(first))
                .andExpect(jsonPath("$[1].orderId").value(second))
                .andExpect(jsonPath("$[2].orderId").value(sell))
                .andExpect(jsonPath("$[*].status").value(everyItem(is("CANCELED"))));

        assertThat(locked(trader, "USDT")).isZero();
        assertThat(locked(trader, base())).isZero();
        as(trader, delete(PAPER + "/orders/open").param("pairId", pair.toString()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        as(trader, delete(PAPER + "/orders/open"), null).andExpect(status().isBadRequest());
        assertLedgerAddsUpToTheBalances(trader);
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
                    new BigDecimal("92"),
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
    // PT-F2
    // ------------------------------------------------------------------------------------------

    /**
     * PT-R04: an OTOCO buy without an OCO quantity sells what the buy brings in net of the fee, floored to the step,
     * so its OCO is placed instead of REJECTED; the quantity shows on the pending legs before the buy fills.
     */
    @Test
    void PTR04_anOtocoBuyWithoutAnOcoQuantity_sellsWhatTheBuyLeavesAfterItsFee() throws Exception {
        UUID trader = trader();
        fineSteps();
        price("60000");

        String waiting = placeList(trader, otoco("59000", "0.01", null, "66000", "55000", "54900"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders[1].status").value("PENDING_NEW"))
                .andExpect(jsonPath("$.orders[1].quantity").value(0.00999))
                .andExpect(jsonPath("$.orders[2].quantity").value(0.00999))
                .andReturn()
                .getResponse()
                .getContentAsString();
        as(trader, post(PAPER + "/order-lists/" + JsonPath.read(waiting, "$.listId") + "/cancel"), null)
                .andExpect(status().isOk());

        depth(List.of(level("59990", "5")), List.of(level("59980", "5")));
        placeList(trader, otoco("60000", "0.01", null, "66000", "55000", "54900"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.orders[0].status").value("FILLED"))
                .andExpect(jsonPath("$.orders[1].status").value("NEW"))
                .andExpect(jsonPath("$.orders[1].quantity").value(0.00999))
                .andExpect(jsonPath("$.orders[2].status").value("NEW"));

        assertThat(locked(trader, base())).isEqualByComparingTo("0.00999");
        PaperLedgerInvariant.assertHolds(sql, accountOf(trader));
    }

    /** PT-R04: an OTOCO that gives its OCO quantity keeps it, so selling the whole buy is still REJECTED. */
    @Test
    void PTR04_anOtocoBuyWithTheBuysQuantityAsOcoQuantity_isStillRejected() throws Exception {
        UUID trader = trader();
        fineSteps();
        price("60000");
        depth(List.of(level("59990", "5")), List.of(level("59980", "5")));

        placeList(trader, otoco("60000", "0.01", "0.01", "66000", "55000", "54900"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ALL_DONE"))
                .andExpect(jsonPath("$.orders[0].status").value("FILLED"))
                .andExpect(jsonPath("$.orders[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.orders[1].statusReason").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.orders[2].status").value("REJECTED"));

        assertThat(spot(trader, base())).isEqualByComparingTo("0.00999");
        PaperLedgerInvariant.assertHolds(sql, accountOf(trader));
    }

    /** PT-R04: a plain OCO still needs its quantity. */
    @Test
    void PTR04_anOcoWithoutAQuantity_isMsg01() throws Exception {
        UUID trader = trader();
        price("100");

        placeList(
                        trader,
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OCO\", \"oco\": {\"side\":"
                                + " \"SELL\", \"takeProfitPrice\": 110, \"stopPrice\": 95, \"stopLimitPrice\": 94}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['oco.quantity']").value("MSG01"));
    }

    /**
     * PT-R14: a replayed STOP_LOSS sell executes at the open of a candle that opened below its stop, and at its stop
     * when the candle opened above it and traded down through it; a live one takes the book as before, and a replayed
     * TAKE_PROFIT sell executes at the open of a candle that opened above its stop (Q-51).
     */
    @Test
    void PTR14_aReplayedMarketStop_fillsAtTheOpenOfACandleThatGappedPastIt_elseAtItsStop() throws Exception {
        UUID trader = trader();
        fineSteps();
        price("61000");
        place(trader, market("BUY", "\"quantity\": 0.03")).andExpect(status().isCreated());
        String gapped = id(place(trader, stop("STOP_LOSS", "SELL", "60000", null, null, "0.009")));
        String crossed = id(place(trader, stop("STOP_LOSS", "SELL", "60000", null, null, "0.009")));
        String live = id(place(trader, stop("STOP_LOSS", "SELL", "60000", null, null, "0.009")));
        String takeProfit = id(place(trader, stop("TAKE_PROFIT", "SELL", "62000", null, null, "0.002")));
        Instant at = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
        BigDecimal stop = new BigDecimal("60000");

        assertThat(matching.trigger(watched(gapped), at, stop, FillSource.REPLAY, new BigDecimal("58000")))
                .isTrue();
        assertThat(matching.trigger(watched(crossed), at, stop, FillSource.REPLAY, new BigDecimal("60500")))
                .isTrue();
        assertThat(matching.trigger(
                        watched(takeProfit), at, new BigDecimal("62000"), FillSource.REPLAY, new BigDecimal("62500")))
                .isTrue();
        depth(List.of(level("59600", "5")), List.of(level("59500", "5")));
        assertThat(matching.trigger(watched(live), at, stop, FillSource.LIVE, new BigDecimal("58000")))
                .isTrue();

        as(trader, get(PAPER + "/orders/" + gapped), null)
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.avgPrice").value(58000.0));
        as(trader, get(PAPER + "/orders/" + crossed), null)
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.avgPrice").value(60000.0));
        as(trader, get(PAPER + "/orders/" + live), null)
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.avgPrice").value(59500.0));
        as(trader, get(PAPER + "/orders/" + takeProfit), null)
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.avgPrice").value(62500.0));
        assertThat(sql.sql("select fill_source from paper_fill where order_id = ?")
                        .param(UUID.fromString(gapped))
                        .query(String.class)
                        .single())
                .isEqualTo("REPLAY");
        PaperLedgerInvariant.assertHolds(sql, accountOf(trader));
    }

    /**
     * Q-51: a replayed STOP_LOSS or TAKE_PROFIT, either side, fills at the triggering candle's open when its trigger
     * condition already held there, and at its stop when the candle opened on the other side and crossed it; a live
     * TAKE_PROFIT takes the book as before.
     */
    @Test
    void Q51_aReplayedMarketStop_fillsAtTheOpenWhenItsConditionHeldThere_elseAtItsStop() throws Exception {
        UUID trader = trader();
        fineSteps();
        price("61000");
        place(trader, market("BUY", "\"quantity\": 0.05")).andExpect(status().isCreated());
        Map<String, String> filledAt = new LinkedHashMap<>();
        Map<String, String> openAt = new LinkedHashMap<>();
        // Placed above 60,000: a STOP_LOSS sell and a TAKE_PROFIT buy wait below it.
        for (String type : List.of("STOP_LOSS SELL", "TAKE_PROFIT BUY")) {
            String[] parts = type.split(" ");
            String gap = id(place(trader, stop(parts[0], parts[1], "60000", null, null, "0.002")));
            String cross = id(place(trader, stop(parts[0], parts[1], "60000", null, null, "0.002")));
            boolean sell = parts[1].equals("SELL");
            openAt.put(gap, sell ? "58000" : "59000");
            filledAt.put(gap, sell ? "58000" : "59000");
            openAt.put(cross, "60500");
            filledAt.put(cross, "60000");
        }
        price("59000");
        // Placed below 60,000: a TAKE_PROFIT sell and a STOP_LOSS buy wait above it.
        for (String type : List.of("TAKE_PROFIT SELL", "STOP_LOSS BUY")) {
            String[] parts = type.split(" ");
            String gap = id(place(trader, stop(parts[0], parts[1], "60000", null, null, "0.002")));
            String cross = id(place(trader, stop(parts[0], parts[1], "60000", null, null, "0.002")));
            openAt.put(gap, "61000");
            filledAt.put(gap, "61000");
            openAt.put(cross, "59500");
            filledAt.put(cross, "60000");
        }
        String live = id(place(trader, stop("TAKE_PROFIT", "SELL", "60000", null, null, "0.002")));
        Instant at = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
        BigDecimal stop = new BigDecimal("60000");

        for (Map.Entry<String, String> replayed : openAt.entrySet()) {
            assertThat(matching.trigger(
                            watched(replayed.getKey()),
                            at,
                            stop,
                            FillSource.REPLAY,
                            new BigDecimal(replayed.getValue())))
                    .isTrue();
        }
        depth(List.of(level("60200", "5")), List.of(level("60100", "5")));
        assertThat(matching.trigger(watched(live), at, stop, FillSource.LIVE, new BigDecimal("61000")))
                .isTrue();

        for (Map.Entry<String, String> expected : filledAt.entrySet()) {
            as(trader, get(PAPER + "/orders/" + expected.getKey()), null)
                    .andExpect(jsonPath("$.status").value("FILLED"))
                    .andExpect(jsonPath("$.avgPrice").value(Double.parseDouble(expected.getValue())));
        }
        as(trader, get(PAPER + "/orders/" + live), null)
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.avgPrice").value(60100.0));
        PaperLedgerInvariant.assertHolds(sql, accountOf(trader));
    }

    /**
     * PT-R03: a cancel and an engine fill of the same LIMIT order, released together, never both win: the order ends
     * either CANCELED with its lock given back, or FILLED paid from its lock, and the ledger adds up each time.
     */
    @Test
    void PTR03_aCancelAndAFillOfTheSameOrder_atOnce_exactlyOneWins() throws Exception {
        UUID trader = trader();
        price("100");
        UUID account = accountOf(trader);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int fills = 0;
        try {
            for (int i = 0; i < 50; i++) {
                String id = id(place(trader, limit("BUY", "90", "1", null)).andExpect(status().isCreated()));
                RestingOrder resting = new RestingOrder(
                        UUID.fromString(id),
                        account,
                        MarketType.SPOT,
                        pair,
                        OrderSide.BUY,
                        new BigDecimal("90"),
                        Instant.now().minusSeconds(1));
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Integer> cancel = pool.submit(() -> {
                    start.await();
                    return as(trader, post(PAPER + "/orders/" + id + "/cancel"), null)
                            .andReturn()
                            .getResponse()
                            .getStatus();
                });
                Future<Boolean> fill = pool.submit(() -> {
                    start.await();
                    return matching.fill(resting, Instant.now(), FillSource.LIVE);
                });
                boolean filled = fill.get(30, TimeUnit.SECONDS);
                int cancelled = cancel.get(30, TimeUnit.SECONDS);

                assertThat(filled)
                        .as("iteration %s: the cancel answered %s", i, cancelled)
                        .isEqualTo(cancelled != 200);
                assertThat(sql.sql("select order_status from paper_order where order_id = ?")
                                .param(UUID.fromString(id))
                                .query(String.class)
                                .single())
                        .isEqualTo(filled ? "FILLED" : "CANCELED");
                fills += filled ? 1 : 0;
                assertThat(locked(trader, "USDT")).isZero();
                assertThat(spot(trader, "USDT")).isEqualByComparingTo(BigDecimal.valueOf(10000 - 90L * fills));
                PaperLedgerInvariant.assertHolds(sql, account);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * PT-R03: two LIMIT buys of one Trader, each needing more than half of the free USDT, placed together: one is
     * accepted and the other refused, and the free balance never goes below zero.
     */
    @Test
    void PTR03_twoBuysOfOneTraderRacingForTheSameBalance_oneIsRefused() throws Exception {
        UUID trader = trader();
        price("100");
        UUID account = accountOf(trader);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 50; i++) {
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<MvcResult>> placed = new ArrayList<>();
                for (int buyer = 0; buyer < 2; buyer++) {
                    placed.add(pool.submit(() -> {
                        start.await();
                        return place(trader, limit("BUY", "90", "60", null)).andReturn();
                    }));
                }
                List<MvcResult> results = new ArrayList<>();
                for (Future<MvcResult> result : placed) {
                    results.add(result.get(30, TimeUnit.SECONDS));
                }

                assertThat(results)
                        .extracting(result -> result.getResponse().getStatus())
                        .as("iteration %s", i)
                        .containsExactlyInAnyOrder(201, 400);
                MvcResult accepted = results.stream()
                        .filter(result -> result.getResponse().getStatus() == 201)
                        .findFirst()
                        .orElseThrow();
                MvcResult refused = results.stream()
                        .filter(result -> result.getResponse().getStatus() == 400)
                        .findFirst()
                        .orElseThrow();
                assertThat(JsonPath.<String>read(refused.getResponse().getContentAsString(), "$.code"))
                        .isEqualTo("PAPER_INSUFFICIENT_BALANCE");
                assertThat(free(trader, "USDT")).isEqualByComparingTo("4600");
                PaperLedgerInvariant.assertHolds(sql, account);
                String id = JsonPath.read(accepted.getResponse().getContentAsString(), "$.orderId");
                as(trader, post(PAPER + "/orders/" + id + "/cancel"), null).andExpect(status().isOk());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(free(trader, "USDT")).isEqualByComparingTo("10000");
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Gives the test's pair a step of 0.00001, so it trades at BTC-like prices. */
    private void fineSteps() {
        sql.sql("update crypto_pair set spot_step_size = 0.00001 where pair_id = ?")
                .param(pair)
                .update();
    }

    /** The stop as the engine watches it. */
    private TriggerOrder watched(String id) {
        return matching.triggerOrders().stream()
                .filter(order -> order.orderId().toString().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private BigDecimal free(UUID trader, String coin) {
        return spot(trader, coin).subtract(locked(trader, coin));
    }

    private String market(String side, String size) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side + "\", \"type\": \"MARKET\", "
                + size + "}";
    }

    private ResultActions placeList(UUID trader, String body) throws Exception {
        return as(trader, post(PAPER + "/order-lists"), body);
    }

    private String oco(String side, String quantity, String takeProfit, String stop, String stopLimit) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OCO\", \"oco\": "
                + ocoPart(side, quantity, takeProfit, stop, stopLimit) + "}";
    }

    private String otoco(
            String buyPrice, String buyQuantity, String ocoQuantity, String takeProfit, String stop, String stopLimit) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"type\": \"OTOCO\","
                + " \"working\": {\"side\": \"BUY\", \"type\": \"LIMIT\", \"price\": " + buyPrice + ", \"quantity\": "
                + buyQuantity + "}, \"oco\": " + ocoPart("SELL", ocoQuantity, takeProfit, stop, stopLimit) + "}";
    }

    /** The OCO part of a group; a {@code null} quantity is left out. */
    private static String ocoPart(String side, String quantity, String takeProfit, String stop, String stopLimit) {
        return "{\"side\": \"" + side + "\"" + (quantity == null ? "" : ", \"quantity\": " + quantity)
                + ", \"takeProfitPrice\": " + takeProfit
                + ", \"stopPrice\": " + stop + ", \"stopLimitPrice\": " + stopLimit + "}";
    }

    /** A Spot stop of the test's pair; a {@code null} stop, delta or price is left out. */
    private String stop(String type, String side, String stop, String trailingDelta, String price, String quantity) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side + "\", \"type\": \"" + type
                + "\"" + (stop == null ? "" : ", \"stopPrice\": " + stop)
                + (trailingDelta == null ? "" : ", \"trailingDelta\": " + trailingDelta)
                + (price == null ? "" : ", \"price\": " + price) + ", \"quantity\": " + quantity + "}";
    }

    /** One market trade of the test's pair, {@code second} seconds from now, handed to the engine as it streams. */
    private void trade(String price, int second) {
        Instant at = Instant.now().plusSeconds(second);
        worker.onTrade(new MarketTrade(
                MarketType.SPOT, pair, TICKS.incrementAndGet(), new BigDecimal(price), BigDecimal.ONE, at, false, at));
    }

    private String limitMaker(String side, String price, String quantity) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side + "\","
                + " \"type\": \"LIMIT_MAKER\", \"price\": " + price + ", \"quantity\": " + quantity + "}";
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

    /** Streams a depth20 snapshot of the test's pair, newer than every earlier one. */
    private void depth(List<PriceLevel> asks, List<PriceLevel> bids) {
        trading.recordDepth(
                new OrderBookDepth(MarketType.SPOT, pair, TICKS.incrementAndGet(), bids, asks, Instant.now()));
    }

    private static PriceLevel level(String price, String quantity) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(quantity));
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
