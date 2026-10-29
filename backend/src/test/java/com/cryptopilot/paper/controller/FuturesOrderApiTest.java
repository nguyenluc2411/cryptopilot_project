package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.OrderBookDepth;
import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.market.service.impl.TradingMarketData;
import com.cryptopilot.paper.dto.response.FuturesAccountResponse;
import com.cryptopilot.paper.dto.response.FuturesOrderUpdateResponse;
import com.cryptopilot.paper.job.FuturesFundingJob;
import com.cryptopilot.paper.model.LiquidationLevel;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import com.cryptopilot.paper.service.PaperMatchingService;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.CustomMatcher;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Paper Futures orders end to end, on the database: placement with the exchange's refusals and codes, execution on
 * arrival against the Futures book, a waiting order filled by the engine, and what each fill does to the one-way
 * position, the Futures wallet, the isolated wallet and its audit rows; then the positions and the account read at
 * the mark, the transfer out limited by what can be withdrawn, the margin of an isolated position and the closes;
 * what each committed change pushes on the Trader's Futures queues; what a stale mark price refuses; and the
 * conditional orders, placed, triggered and ended; the liquidation of isolated positions; and funding.
 *
 * <p>The Futures wallet is opened with 5,000 USDT; fees are zero.
 *
 * <p>Rule: TR-15, TR-16, TR-17, TR-18, TR-19, TR-20, TR-21, TR-22, TR-22a, TR-24, TR-41; D-127; Q-T10.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class FuturesOrderApiTest {

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

    @Autowired
    private TradingMarketData trading;

    @Autowired
    private PaperMatchingService matching;

    @MockitoSpyBean
    private SimpMessageSendingOperations broker;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private FuturesFundingJob funding;

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "FO" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5,
                               futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 5
                         where pair_id = ?""").param(pair).update();
        bracket(1, "0", "50000", 125, "0.004", "0");
        bracket(2, "50000", "600000", 100, "0.005", "50");
        bracket(3, "600000", "3000000", 20, "0.0065", "950");
        mark("100");
    }

    @AfterEach
    void removeRows() {
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
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(trader).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(trader)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        trading.retainOnly(MarketType.FUTURES, List.of());
        sql.sql("delete from funding_rate_history where pair_id = ?")
                .param(pair)
                .update();
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
    // Placement and refusals
    // ------------------------------------------------------------------------------------------

    @Test
    void QT10_aTraderWithoutTheFuturesFeature_isRefused() throws Exception {
        UUID trader = trader(false);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));

        place(trader, market("BUY", "1")).andExpect(status().isForbidden());
        assertThat(orderCount(trader)).isZero();
    }

    @Test
    void TR15_theShapeOfTheType_isChecked_andConditionalTypesAreAlgoOrders() throws Exception {
        UUID trader = trader(true);

        place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "STOP_MARKET", "quantity": "1"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type").value("MSG15"));
        place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "LIMIT", "quantity": "1"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG01"));
        place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "MARKET", "quantity": "1", "price": "100"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG15"));
        place(trader, """
                        {"symbol": "%s", "side": "BUY", "positionSide": "LONG", "type": "MARKET", "quantity": "1"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_POSITION_SIDE"))
                .andExpect(jsonPath("$.binanceCode").value(-4061));
        assertThat(orderCount(trader)).isZero();
    }

    @Test
    void TR15_theFiltersOfTheFuturesPair_areChecked_neverRounded() throws Exception {
        UUID trader = trader(true);

        place(trader, limit("BUY", "GTC", "100.05", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_PRICE_FILTER"));
        place(trader, limit("BUY", "GTC", "100", "0.0005"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_LOT_SIZE"));
        place(trader, limit("BUY", "GTC", "100", "0.01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_NOTIONAL"));
        assertThat(orderCount(trader)).isZero();
    }

    @Test
    void TR15_anOrderCostingMoreThanTheAvailableBalance_isRefused() throws Exception {
        UUID trader = trader(true);
        leverage(trader, 1);

        place(trader, limit("BUY", "GTC", "99", "60"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_INSUFFICIENT_MARGIN"))
                .andExpect(jsonPath("$.binanceCode").value(-2019))
                .andExpect(jsonPath("$.binanceMsg").value("Margin is insufficient."));
        // 50 at 99, 1x: 4,950 of the 5,000 available.
        place(trader, limit("BUY", "GTC", "99", "50")).andExpect(status().isOk());
        // The waiting order's margin is no longer available.
        place(trader, limit("BUY", "GTC", "99", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.binanceCode").value(-2019));
    }

    @Test
    void TR15_aPositionPastTheLeveragesLargestNotional_isRefused() throws Exception {
        UUID trader = trader(true);
        leverage(trader, 125);

        // 125x allows 50,000 of notional: 501 at 99.9 is 50,049.9.
        place(trader, limit("BUY", "GTC", "99.9", "501"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_MAX_POSITION_AT_LEVERAGE"))
                .andExpect(jsonPath("$.binanceCode").value(-2027));
    }

    @Test
    void TR15_aReduceOnlyOrderWithoutAPosition_isRefused() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));

        place(trader, """
                        {"symbol": "%s", "side": "SELL", "type": "MARKET", "quantity": "1", "reduceOnly": true}""".formatted(symbol))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_REDUCE_ONLY_REJECTED"))
                .andExpect(jsonPath("$.binanceCode").value(-2022));
    }

    @Test
    void TR15_aPostOnlyThatWouldTake_isRefused_andAFokTheBookCannotFill_expires() throws Exception {
        UUID trader = trader(true);
        // Only 0.5 at or below 100: the next level is past the FOK's price.
        depth(List.of(level("100", "0.5"), level("100.5", "5")), List.of(level("99.9", "5")));

        place(trader, limit("BUY", "GTX", "100", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_WOULD_TAKE"))
                .andExpect(jsonPath("$.binanceCode").value(-5022));
        place(trader, limit("BUY", "FOK", "100", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.executedQty").value(numeric("0")));
        assertThat(openPositions(trader)).isZero();
    }

    @Test
    void TR15_aKeySentAgain_returnsTheOrderPlaced_andAKeyReusedForAnotherOrder_isAConflict() throws Exception {
        UUID trader = trader(true);
        String body = """
                {"symbol": "%s", "side": "BUY", "type": "LIMIT", "price": "90", "quantity": "1",
                 "newClientOrderId": "web-1"}""".formatted(symbol);

        String first = id(place(trader, body).andExpect(status().isOk()));
        place(trader, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(first));
        place(trader, body.replace("\"90\"", "\"91\"")).andExpect(status().isConflict());
        assertThat(orderCount(trader)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------
    // Waiting orders, cancels and reads
    // ------------------------------------------------------------------------------------------

    @Test
    void TR15_aLimitBelowTheBook_waits_isListed_andCancelled_andNeverShowsOnSpot() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));

        String id = id(place(trader, limit("BUY", "GTC", "95", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.positionSide").value("BOTH"))
                .andExpect(jsonPath("$.reduceOnly").value(false)));

        as(trader, get(FUTURES + "/openOrders"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(id))
                .andExpect(jsonPath("$[0].symbol").value(symbol));
        as(trader, get("/api/v1/paper/orders/open"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        as(trader, get("/api/v1/paper/orders/" + id), null).andExpect(status().isNotFound());
        as(trader, get(FUTURES + "/order").param("symbol", symbol).param("orderId", id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(numeric("95.0")));

        as(trader, delete(FUTURES + "/order").param("symbol", symbol).param("orderId", id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        as(trader, delete(FUTURES + "/order").param("symbol", symbol).param("orderId", id), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-2011));
        as(trader, get(FUTURES + "/allOrders").param("symbol", symbol), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CANCELED"));
    }

    @Test
    void TR15_cancelAll_endsEveryWorkingOrderOfThePair() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, limit("BUY", "GTC", "95", "1")).andExpect(status().isOk());
        place(trader, limit("SELL", "GTC", "105", "1")).andExpect(status().isOk());

        as(trader, delete(FUTURES + "/allOpenOrders").param("symbol", symbol), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        as(trader, get(FUTURES + "/openOrders").param("symbol", symbol), null)
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------------------------------------------------
    // Executions and the position
    // ------------------------------------------------------------------------------------------

    @Test
    void TR16_aMarketBuy_takesTheLevels_andOpensACrossLongAtTheirAverage() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "0.5"), level("101", "2")), List.of(level("99.9", "5")));

        String id = id(place(trader, market("BUY", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.executedQty").value(numeric("1.0")))
                .andExpect(jsonPath("$.avgPrice").value(numeric("100.5"))));

        Map<String, Object> position = position(trader);
        assertThat(position.get("direction")).isEqualTo("LONG");
        assertThat(position.get("margin_type")).isEqualTo("CROSSED");
        assertThat((BigDecimal) position.get("quantity")).isEqualByComparingTo("1");
        assertThat((BigDecimal) position.get("entry_price")).isEqualByComparingTo("100.5");
        assertThat(position.get("isolated_wallet_amount")).isNull();
        as(trader, get(FUTURES + "/userTrades").param("symbol", symbol), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderId").value(id))
                .andExpect(jsonPath("$[0].maker").value(false))
                .andExpect(jsonPath("$[0].buyer").value(true))
                .andExpect(jsonPath("$[0].commissionAsset").value("USDT"))
                .andExpect(jsonPath("$[0].realizedPnl").value(numeric("0")));
        assertThat(wallet(trader)).isEqualByComparingTo("5000");
    }

    @Test
    void TR16_closingAtAProfit_realisesItIntoTheFuturesWallet() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());

        depth(List.of(level("110.1", "5")), List.of(level("110", "5")));
        place(trader, market("SELL", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"));

        assertThat(openPositions(trader)).isZero();
        Map<String, Object> closed = sql.sql("select * from futures_position where user_id = ?")
                .param(trader)
                .query()
                .singleRow();
        assertThat(closed.get("position_status")).isEqualTo("CLOSED");
        assertThat(closed.get("close_reason")).isEqualTo("MANUAL");
        assertThat((BigDecimal) closed.get("realized_pnl_amount")).isEqualByComparingTo("10");
        assertThat((BigDecimal) closed.get("close_avg_price")).isEqualByComparingTo("110");
        assertThat(wallet(trader)).isEqualByComparingTo("5010");
        assertThat(ledgerSum(trader)).isEqualByComparingTo(wallet(trader));
    }

    @Test
    void TR16_aReversal_closesTheLong_andOpensAShort_inTwoFills() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());

        depth(List.of(level("110.1", "5")), List.of(level("110", "5")));
        String id = id(place(trader, market("SELL", "1.5")).andExpect(status().isOk()));

        List<Map<String, Object>> fills =
                sql.sql("""
                        select f.fill_quantity, f.realized_pnl_amount, p.direction, p.position_status
                          from order_fill f join futures_position p on p.position_id = f.position_id
                         where f.order_id = ? order by p.direction""").param(UUID.fromString(id)).query().listOfRows();
        assertThat(fills).hasSize(2);
        assertThat(fills.get(0).get("direction")).isEqualTo("LONG");
        assertThat(fills.get(0).get("position_status")).isEqualTo("CLOSED");
        assertThat((BigDecimal) fills.get(0).get("fill_quantity")).isEqualByComparingTo("1");
        assertThat((BigDecimal) fills.get(0).get("realized_pnl_amount")).isEqualByComparingTo("10");
        assertThat(fills.get(1).get("direction")).isEqualTo("SHORT");
        assertThat(fills.get(1).get("position_status")).isEqualTo("OPEN");
        assertThat((BigDecimal) fills.get(1).get("fill_quantity")).isEqualByComparingTo("0.5");
        Map<String, Object> position = position(trader);
        assertThat((BigDecimal) position.get("entry_price")).isEqualByComparingTo("110");
        assertThat(wallet(trader)).isEqualByComparingTo("5010");
    }

    @Test
    void TR16_anIsolatedPosition_takesItsMarginFromTheCrossWallet_andReleasesItWhenClosed() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));

        place(trader, market("BUY", "2")).andExpect(status().isOk());

        Map<String, Object> position = position(trader);
        UUID positionId = (UUID) position.get("position_id");
        // 2 at 100, 10x, the mark at 100: no open loss.
        assertThat((BigDecimal) position.get("isolated_wallet_amount")).isEqualByComparingTo("20");
        depth(List.of(level("95.1", "5")), List.of(level("95", "5")));
        place(trader, market("SELL", "1")).andExpect(status().isOk());
        assertThat((BigDecimal) position(trader).get("isolated_wallet_amount")).isEqualByComparingTo("10");

        place(trader, market("SELL", "1")).andExpect(status().isOk());

        assertThat(openPositions(trader)).isZero();
        List<Map<String, Object>> rows = sql.sql("""
                        select adjustment_type, amount, iw_after_amount from futures_margin_adjustment
                         where position_id = ? order by created_at, adjustment_id""").param(positionId).query().listOfRows();
        assertThat(rows).extracting(row -> row.get("adjustment_type")).containsExactly("OPEN", "RELEASE", "CLOSE");
        assertThat(rows.stream().map(row -> (BigDecimal) row.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0");
        assertThat((BigDecimal) rows.getLast().get("iw_after_amount")).isEqualByComparingTo("0");
        // Lost 5 on each unit: the loss stayed within the isolated margin released.
        assertThat(wallet(trader)).isEqualByComparingTo("4990");
        assertThat(ledgerSum(trader)).isEqualByComparingTo(wallet(trader));
    }

    @Test
    void TR16_aReduceOnlyOrderLargerThanThePosition_closesItAndExpiresTheRest() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());

        place(trader, """
                        {"symbol": "%s", "side": "SELL", "type": "MARKET", "quantity": "3", "reduceOnly": true}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.executedQty").value(numeric("1.0")));

        assertThat(openPositions(trader)).isZero();
    }

    @Test
    void TR16_aWaitingLimit_filledByTheEngine_opensThePositionAsAMaker() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        String id = id(place(trader, limit("BUY", "GTC", "98", "1")).andExpect(status().isOk()));

        boolean filled = matching.fill(
                new RestingOrder(
                        UUID.fromString(id),
                        trader,
                        MarketType.FUTURES,
                        pair,
                        OrderSide.BUY,
                        new BigDecimal("98"),
                        Instant.now().minusSeconds(1)),
                Instant.now(),
                FillSource.LIVE);

        assertThat(filled).isTrue();
        as(trader, get(FUTURES + "/order").param("symbol", symbol).param("orderId", id), null)
                .andExpect(jsonPath("$.status").value("FILLED"));
        assertThat((BigDecimal) position(trader).get("entry_price")).isEqualByComparingTo("98");
        as(trader, get(FUTURES + "/userTrades").param("symbol", symbol), null)
                .andExpect(jsonPath("$[0].maker").value(true));
    }

    @Test
    void TR15_anIocLimit_takesWhatIsThere_andTheRestExpires() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "0.4"), level("100.5", "5")), List.of(level("99.9", "5")));

        place(trader, limit("BUY", "IOC", "100", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("NOT_FILLABLE_ON_ARRIVAL"))
                .andExpect(jsonPath("$.executedQty").value(numeric("0.4")));
        assertThat((BigDecimal) position(trader).get("quantity")).isEqualByComparingTo("0.4");
    }

    @Test
    void TR15_withoutACurrentPrice_aMarketOrIocOrder_isRefused_andAGtcWaits() throws Exception {
        UUID trader = trader(true);

        place(trader, market("BUY", "1")).andExpect(status().isServiceUnavailable());
        place(trader, limit("BUY", "IOC", "100", "1")).andExpect(status().isServiceUnavailable());
        place(trader, limit("BUY", "GTC", "100", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void TR15_anOrder_isNamedByItsIdOrItsClientOrderId_onItsPairOnly() throws Exception {
        UUID trader = trader(true);
        place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "LIMIT", "price": "90", "quantity": "1",
                         "newClientOrderId": "web-2"}""".formatted(symbol)).andExpect(status().isOk());

        as(trader, get(FUTURES + "/order").param("symbol", symbol), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.orderId").value("MSG01"));
        as(trader, get(FUTURES + "/order").param("symbol", symbol).param("origClientOrderId", "nope"), null)
                .andExpect(status().isNotFound());
        as(trader, delete(FUTURES + "/order").param("symbol", symbol).param("origClientOrderId", "web-2"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.clientOrderId").value("web-2"));
        as(trader, get(FUTURES + "/allOrders").param("symbol", symbol).param("limit", "0"), null)
                .andExpect(status().isBadRequest());
        as(
                        trader,
                        get(FUTURES + "/userTrades")
                                .param("symbol", symbol)
                                .param("startTime", "2026-10-02T00:00:00Z")
                                .param("endTime", "2026-10-01T00:00:00Z"),
                        null)
                .andExpect(status().isBadRequest());
        as(trader, get(FUTURES + "/userTrades").param("symbol", symbol).param("limit", "1000"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void TR15_whileALiquidationRuns_orInHedgeMode_noOrderIsTaken() throws Exception {
        UUID trader = trader(true);
        as(trader, get(FUTURES + "/accountConfig"), null).andExpect(status().isOk());

        sql.sql("update futures_account_config set dual_side_position = true where user_id = ?")
                .param(trader)
                .update();
        place(trader, market("BUY", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.binanceCode").value(-4061));
        sql.sql("update futures_account_config set liquidating_since = now() where user_id = ?")
                .param(trader)
                .update();
        place(trader, market("BUY", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-2023));
    }

    @Test
    void TR16_onceThePositionIsSmaller_theReduceOnlyOrdersThatNoLongerFit_expire_newestFirst() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        String older = id(place(trader, """
                        {"symbol": "%s", "side": "SELL", "type": "LIMIT", "price": "120", "quantity": "0.4",
                         "reduceOnly": true}""".formatted(symbol)).andExpect(status().isOk()));
        String newer = id(place(trader, """
                        {"symbol": "%s", "side": "SELL", "type": "LIMIT", "price": "121", "quantity": "0.4",
                         "reduceOnly": true}""".formatted(symbol)).andExpect(status().isOk()));

        place(trader, market("SELL", "0.5")).andExpect(status().isOk());

        assertThat(statusOf(older)).isEqualTo("NEW");
        assertThat(statusOf(newer)).isEqualTo("EXPIRED");
        place(trader, market("SELL", "0.5")).andExpect(status().isOk());
        assertThat(statusOf(older)).isEqualTo("EXPIRED");
    }

    @Test
    void TR16_anIsolatedLossBeyondItsWallet_isAnInsuranceCover_andNeverReachesTheCrossWallet() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        UUID positionId = (UUID) position(trader).get("position_id");

        // 15 lost on a wallet of 10: the 5 beyond it is covered.
        depth(List.of(level("85.1", "5")), List.of(level("85", "5")));
        place(trader, market("SELL", "1")).andExpect(status().isOk());

        assertThat(wallet(trader)).isEqualByComparingTo("4990");
        assertThat(ledgerSum(trader)).isEqualByComparingTo(wallet(trader));
        assertThat(sql.sql("""
                                select amount from wallet_ledger_entry
                                 where user_id = ? and entry_type = 'INSURANCE_COVER'""").param(trader).query(BigDecimal.class).single()).isEqualByComparingTo("5");
        Map<String, Object> close = sql.sql("""
                        select amount, ref_type from futures_margin_adjustment
                         where position_id = ? and adjustment_type = 'CLOSE'""").param(positionId).query().singleRow();
        assertThat((BigDecimal) close.get("amount")).isEqualByComparingTo("-10");
        assertThat(close.get("ref_type")).isEqualTo("LEDGER");
    }

    @Test
    void TR16_aCrossWalletLeftBelowZeroWithNoCrossPosition_isMadeGoodByAnInsuranceCover() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "500")), List.of(level("99.9", "500")));
        place(trader, market("BUY", "100")).andExpect(status().isOk());

        // 6,000 lost on a wallet of 5,000.
        depth(List.of(level("40.1", "500")), List.of(level("40", "500")));
        place(trader, market("SELL", "100")).andExpect(status().isOk());

        assertThat(wallet(trader)).isEqualByComparingTo("0");
        assertThat(ledgerSum(trader)).isEqualByComparingTo("0");
    }

    @Test
    void TR16_anIsolatedOrderThatCanNoLongerBeFundedAtItsFill_expires() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        String id = id(place(trader, limit("BUY", "GTC", "98", "1")).andExpect(status().isOk()));
        // The cross wallet emptied meanwhile.
        sql.sql("update wallet_balance set free_amount = 1 where user_id = ? and wallet_type = 'FUTURES'")
                .param(trader)
                .update();

        matching.fill(
                new RestingOrder(
                        UUID.fromString(id),
                        trader,
                        MarketType.FUTURES,
                        pair,
                        OrderSide.BUY,
                        new BigDecimal("98"),
                        Instant.now().minusSeconds(1)),
                Instant.now(),
                FillSource.LIVE);

        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(openPositions(trader)).isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Positions and the account at the mark
    // ------------------------------------------------------------------------------------------

    /** Plan 4.5, example 1: Long 0.1 at 60,000, 10x, a wallet of 600, MMR 0.4 %: liquidated at 54,216.87. */
    @Test
    void TR18_anIsolatedLong_showsItsLiquidationPrice_marginAndMarginRatio() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        mark("60000");
        depth(List.of(level("60000", "5")), List.of(level("59999.9", "5")));
        place(trader, market("BUY", "0.1")).andExpect(status().isOk());

        as(trader, get(FUTURES + "/positionRisk").param("symbol", symbol), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].positionAmt").value(numeric("0.1")))
                .andExpect(jsonPath("$[0].entryPrice").value(numeric("60000")))
                .andExpect(jsonPath("$[0].breakEvenPrice").value(numeric("60000")))
                .andExpect(jsonPath("$[0].markPrice").value(numeric("60000")))
                .andExpect(jsonPath("$[0].liquidationPrice").value(numeric("54216.9")))
                .andExpect(jsonPath("$[0].isolatedWallet").value(numeric("600")))
                .andExpect(jsonPath("$[0].isolatedMargin").value(numeric("600")))
                .andExpect(jsonPath("$[0].notional").value(numeric("6000")))
                .andExpect(jsonPath("$[0].positionInitialMargin").value(numeric("600")))
                .andExpect(jsonPath("$[0].maintMargin").value(numeric("24")))
                .andExpect(jsonPath("$[0].marginRatio").value(numeric("0.04")))
                .andExpect(jsonPath("$[0].marginType").value("ISOLATED"))
                .andExpect(jsonPath("$[0].leverage").value(10))
                .andExpect(jsonPath("$[0].marginAsset").value("USDT"));

        mark("59000");
        as(trader, get(FUTURES + "/positionRisk"), null)
                .andExpect(jsonPath("$[0].unRealizedProfit").value(numeric("-100")))
                .andExpect(jsonPath("$[0].isolatedMargin").value(numeric("500")))
                .andExpect(jsonPath("$[0].notional").value(numeric("5900")))
                .andExpect(jsonPath("$[0].liquidationPrice").value(numeric("54216.9")));
        // The isolated wallet is outside the cross wallet, and its loss does not reach the cross side.
        as(trader, get(FUTURES + "/account"), null)
                .andExpect(jsonPath("$.totalWalletBalance").value(numeric("5000")))
                .andExpect(jsonPath("$.totalCrossWalletBalance").value(numeric("4400")))
                .andExpect(jsonPath("$.totalUnrealizedProfit").value(numeric("-100")))
                .andExpect(jsonPath("$.totalMarginBalance").value(numeric("4900")))
                .andExpect(jsonPath("$.availableBalance").value(numeric("4400")))
                .andExpect(jsonPath("$.maxWithdrawAmount").value(numeric("4400")));
    }

    @Test
    void TR18_aCrossPosition_movesTheAvailableBalance_andTheTransferOutIsLimitedByIt() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        place(trader, limit("BUY", "GTC", "90", "1")).andExpect(status().isOk());

        mark("110");
        // 20x: a position margin of 5.5 at 110, an order margin of 4.5, a profit of 10 that cannot leave.
        as(trader, get(FUTURES + "/account"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPositionInitialMargin").value(numeric("5.5")))
                .andExpect(jsonPath("$.totalOpenOrderInitialMargin").value(numeric("4.5")))
                .andExpect(jsonPath("$.totalInitialMargin").value(numeric("10")))
                .andExpect(jsonPath("$.totalMaintMargin").value(numeric("0.44")))
                .andExpect(jsonPath("$.totalCrossUnPnl").value(numeric("10")))
                .andExpect(jsonPath("$.availableBalance").value(numeric("5000")))
                .andExpect(jsonPath("$.maxWithdrawAmount").value(numeric("4990")))
                .andExpect(jsonPath("$.assets[0].asset").value("USDT"))
                .andExpect(jsonPath("$.positions[0].symbol").value(symbol))
                .andExpect(jsonPath("$.positions[0].initialMargin").value(numeric("10")));
        as(trader, get(FUTURES + "/positionRisk"), null)
                .andExpect(jsonPath("$[0].liquidationPrice").value(numeric("0")))
                .andExpect(jsonPath("$[0].bidNotional").value(numeric("90")))
                .andExpect(jsonPath("$[0].openOrderInitialMargin").value(numeric("4.5")));
        as(trader, get(FUTURES + "/balance"), null)
                .andExpect(jsonPath("$[0].balance").value(numeric("5000")))
                .andExpect(jsonPath("$[0].maxWithdrawAmount").value(numeric("4990")));

        as(trader, post("/api/v1/paper/transfers"), """
                        {"asset": "USDT", "from": "FUTURES", "amount": "4990.01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_INSUFFICIENT_BALANCE"));
        as(trader, post("/api/v1/paper/transfers"), """
                        {"asset": "USDT", "from": "FUTURES", "amount": "4990"}""").andExpect(status().isCreated());
        assertThat(wallet(trader)).isEqualByComparingTo("10");
    }

    @Test
    void TR18_withoutAPosition_theAccountIsItsWallet() throws Exception {
        UUID trader = trader(true);

        as(trader, get(FUTURES + "/positionRisk"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        as(trader, get(FUTURES + "/account"), null)
                .andExpect(jsonPath("$.totalWalletBalance").value(numeric("5000")))
                .andExpect(jsonPath("$.availableBalance").value(numeric("5000")))
                .andExpect(jsonPath("$.marginRatio").value(numeric("0")))
                .andExpect(jsonPath("$.positions.length()").value(0));
    }

    // ------------------------------------------------------------------------------------------
    // Margin of an isolated position, and closes
    // ------------------------------------------------------------------------------------------

    /** Plan 4.9: example 1 with 200 added, its liquidation price moves from 54,216.87 to 52,208.84. */
    @Test
    void TR17_marginAdded_toAnIsolatedPosition_movesItsLiquidationPriceAway_andCanBeTakenOutAgain() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        mark("60000");
        depth(List.of(level("60000", "5")), List.of(level("59999.9", "5")));
        place(trader, market("BUY", "0.1")).andExpect(status().isOk());

        positionMargin(trader, "200", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.type").value(1))
                .andExpect(jsonPath("$.isolatedWallet").value(numeric("800")));
        as(trader, get(FUTURES + "/positionRisk"), null)
                .andExpect(jsonPath("$[0].liquidationPrice").value(numeric("52208.9")));
        as(trader, get(FUTURES + "/account"), null)
                .andExpect(jsonPath("$.totalCrossWalletBalance").value(numeric("4200")));

        // At 60,000 and 10x the position needs 600: 200 can come out, not 200.01.
        positionMargin(trader, "200.01", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_POSITION_MARGIN"))
                .andExpect(jsonPath("$.binanceCode").value(-4051));
        positionMargin(trader, "200", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isolatedWallet").value(numeric("600")));
        positionMargin(trader, "5000", 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.binanceCode").value(-2019));

        as(trader, get(FUTURES + "/positionMargin/history").param("symbol", symbol), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value(2))
                .andExpect(jsonPath("$[0].amount").value(numeric("200")))
                .andExpect(jsonPath("$[0].deltaType").value("USER_ADJUST"))
                .andExpect(jsonPath("$[1].type").value(1));
        as(
                        trader,
                        get(FUTURES + "/positionMargin/history")
                                .param("symbol", symbol)
                                .param("type", "1"),
                        null)
                .andExpect(jsonPath("$.length()").value(1));
        as(
                        trader,
                        get(FUTURES + "/positionMargin/history")
                                .param("symbol", symbol)
                                .param("type", "3"),
                        null)
                .andExpect(status().isBadRequest());
        assertThat(wallet(trader)).isEqualByComparingTo("5000");
    }

    @Test
    void TR17_aCrossPosition_orNoPosition_hasNoMarginToChange() throws Exception {
        UUID trader = trader(true);

        positionMargin(trader, "10", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-4054));
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        positionMargin(trader, "10", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-4049));
        positionMargin(trader, "-1", 1).andExpect(status().isBadRequest());
    }

    @Test
    void TR17_aShareOfThePosition_isClosedByAReduceOnlyMarket_roundedDownToTheStep() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());

        close(trader, """
                        {"symbol": "%s", "type": "MARKET", "percent": 33.33}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reduceOnly").value(true))
                .andExpect(jsonPath("$[0].side").value("SELL"))
                .andExpect(jsonPath("$[0].executedQty").value(numeric("0.333")));
        assertThat((BigDecimal) position(trader).get("quantity")).isEqualByComparingTo("0.667");

        close(trader, """
                        {"symbol": "%s", "type": "LIMIT", "price": "120"}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("NEW"))
                .andExpect(jsonPath("$[0].origQty").value(numeric("0.667")));
        close(trader, """
                        {"symbol": "%s", "type": "LIMIT"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG01"));
        close(trader, """
                        {"symbol": "%s", "type": "MARKET", "percent": 0.01}""".formatted(symbol)).andExpect(status().isBadRequest());
    }

    @Test
    void TR17_closeAll_closesEveryPosition_andWithNoneThereIsNothingToClose() throws Exception {
        UUID trader = trader(true);
        close(trader, """
                        {"type": "MARKET"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        close(trader, """
                        {"symbol": "%s", "type": "MARKET"}""".formatted(symbol))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-2022));
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("SELL", "2")).andExpect(status().isOk());

        close(trader, """
                        {"type": "MARKET"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].side").value("BUY"))
                .andExpect(jsonPath("$[0].status").value("FILLED"));

        assertThat(openPositions(trader)).isZero();
        close(trader, """
                        {"type": "LIMIT", "price": "100"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.symbol").value("MSG01"));
    }

    // ------------------------------------------------------------------------------------------
    // Pushes on the Futures queues
    // ------------------------------------------------------------------------------------------

    @Test
    void TR41_anExecution_pushesTheOrderWithItsFills_andTheAccount_onTheFuturesQueuesOnly() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "0.5"), level("101", "2")), List.of(level("99.9", "5")));
        clearInvocations(broker);

        String id = id(place(trader, market("BUY", "1")).andExpect(status().isOk()));

        FuturesOrderUpdateResponse update = (FuturesOrderUpdateResponse) single(trader, "/queue/futures.orders");
        assertThat(update.order().orderId()).hasToString(id);
        assertThat(update.order().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(update.trades()).hasSize(2);
        assertThat(update.trades())
                .allSatisfy(trade -> assertThat(trade.orderId()).hasToString(id));
        FuturesAccountResponse account = (FuturesAccountResponse) single(trader, "/queue/futures.account");
        assertThat(account.positions()).singleElement().satisfies(position -> assertThat(position.positionAmt())
                .isEqualByComparingTo("1"));
        verify(broker, never()).convertAndSendToUser(eq(trader.toString()), eq("/queue/orders"), any(Object.class));
        verify(broker, never()).convertAndSendToUser(eq(trader.toString()), eq("/queue/fills"), any(Object.class));
    }

    @Test
    void TR41_aRefusedOrder_pushesNothing_andAMarginChangeOrATransfer_pushesTheAccount() throws Exception {
        UUID trader = trader(true);
        leverage(trader, 1);
        clearInvocations(broker);

        place(trader, limit("BUY", "GTC", "99", "60")).andExpect(status().isBadRequest());

        verify(broker, never())
                .convertAndSendToUser(eq(trader.toString()), eq("/queue/futures.orders"), any(Object.class));
        verify(broker, never())
                .convertAndSendToUser(eq(trader.toString()), eq("/queue/futures.account"), any(Object.class));

        as(trader, post("/api/v1/paper/transfers"), """
                        {"asset": "USDT", "from": "FUTURES", "amount": "1000"}""").andExpect(status().isCreated());
        FuturesAccountResponse account = (FuturesAccountResponse) single(trader, "/queue/futures.account");
        assertThat(account.totalWalletBalance()).isEqualByComparingTo("4000");

        isolated(trader, 10);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        clearInvocations(broker);
        positionMargin(trader, "5", 1).andExpect(status().isOk());
        account = (FuturesAccountResponse) single(trader, "/queue/futures.account");
        assertThat(account.positions().getFirst().isolatedWallet()).isEqualByComparingTo("15");
        verify(broker, never())
                .convertAndSendToUser(eq(trader.toString()), eq("/queue/futures.orders"), any(Object.class));
    }

    /** The one body pushed to the Trader on the queue since the last clear. */
    private Object single(UUID trader, String queue) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSendToUser(eq(trader.toString()), eq(queue), body.capture());
        return body.getValue();
    }

    // ------------------------------------------------------------------------------------------
    // A stale mark price (MARK_STALE)
    // ------------------------------------------------------------------------------------------

    /** 8.2.1 rules 1 and 2: on a stale pair, what opens risk at once waits, a waiting LIMIT and every close go on. */
    @Test
    void TR22a_onAStalePair_marketAndMarketableOpeningsWait_andClosesGoOn() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());

        staleMark("100");

        place(trader, market("BUY", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_PRICE_UNAVAILABLE"));
        place(trader, limit("BUY", "GTC", "100", "1")).andExpect(status().isServiceUnavailable());
        place(trader, limit("BUY", "GTC", "95", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));
        leverage(trader, 10, status().isServiceUnavailable());
        // Closing never waits for the mark: valued at the book it takes.
        place(trader, market("SELL", "0.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"));
        close(trader, """
                        {"symbol": "%s", "type": "MARKET"}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FILLED"));
        assertThat(openPositions(trader)).isZero();
    }

    /** 8.2.1 rule 3: while a pair of a cross position is stale, nothing opens risk elsewhere and nothing leaves. */
    @Test
    void TR22a_aStaleCrossPosition_holdsTransfersOut_andOpeningsOnOtherPairs() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        String other = "FO" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        UUID otherPair = new MarketTestData(sql, Instant.now()).pair(other, true, true, "TRADING", "TRADING", 1);
        try {
            sql.sql("""
                            update crypto_pair set futures_tick_size = 0.1, futures_step_size = 0.001,
                                   futures_min_notional = 5
                             where pair_id = ?""").param(otherPair).update();
            sql.sql("""
                            insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                          maintenance_margin_rate, maintenance_amount, updated_at)
                            values (?, 1, 0, 3000000, 125, 0.004, 0, now())""").param(otherPair).update();
            prices.record(
                    MarketType.FUTURES,
                    new MarkPriceMessage(
                            other,
                            new BigDecimal("50"),
                            new BigDecimal("50"),
                            BigDecimal.ZERO,
                            Instant.now().plus(Duration.ofHours(1)),
                            Instant.now().plusMillis(TICKS.incrementAndGet())));
            prices.flush();

            staleMark("100");

            as(trader, post(FUTURES + "/order"), """
                            {"symbol": "%s", "side": "BUY", "type": "LIMIT", "price": "40", "quantity": "1"}""".formatted(other)).andExpect(status().isServiceUnavailable());
            as(trader, post("/api/v1/paper/transfers"), """
                            {"asset": "USDT", "from": "FUTURES", "amount": "10"}""").andExpect(status().isServiceUnavailable());
            as(trader, post("/api/v1/paper/transfers"), """
                            {"asset": "USDT", "from": "SPOT", "amount": "10"}""").andExpect(status().isCreated());
        } finally {
            sql.sql("delete from leverage_bracket where pair_id = ?")
                    .param(otherPair)
                    .update();
            sql.sql("delete from trade_order where pair_id = ?")
                    .param(otherPair)
                    .update();
            new MarketTestData(sql, Instant.now()).remove(otherPair);
        }
    }

    @Test
    void TR22a_theMarginOfAPositionOnAStalePair_doesNotChange() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());

        staleMark("100");

        positionMargin(trader, "5", 1).andExpect(status().isServiceUnavailable());
        positionMargin(trader, "1", 2).andExpect(status().isServiceUnavailable());
    }

    /**
     * 8.2.1 rule 4: an isolated order filled while a cross position's pair is stale counts that position at its loss
     * less 1 % of its notional. Here the plain free cash (2,900) would fund the 2,890, the conservative one (2,880) not.
     */
    @Test
    void TR22a_anIsolatedFill_whileACrossPairIsStale_countsItConservatively() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "100")), List.of(level("99.9", "100")));
        place(trader, market("BUY", "40")).andExpect(status().isOk());
        String other = "FO" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        UUID otherPair = new MarketTestData(sql, Instant.now()).pair(other, true, true, "TRADING", "TRADING", 1);
        try {
            sql.sql("""
                            update crypto_pair set futures_tick_size = 0.1, futures_step_size = 0.001,
                                   futures_min_notional = 5
                             where pair_id = ?""").param(otherPair).update();
            sql.sql("""
                            insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                          maintenance_margin_rate, maintenance_amount, updated_at)
                            values (?, 1, 0, 3000000, 125, 0.004, 0, now())""").param(otherPair).update();
            prices.record(
                    MarketType.FUTURES,
                    new MarkPriceMessage(
                            other,
                            new BigDecimal("50"),
                            new BigDecimal("50"),
                            BigDecimal.ZERO,
                            Instant.now().plus(Duration.ofHours(1)),
                            Instant.now().plusMillis(TICKS.incrementAndGet())));
            prices.flush();
            as(trader, post(FUTURES + "/leverage"), """
                            {"symbol": "%s", "leverage": 10}""".formatted(other)).andExpect(status().isOk());
            as(trader, post(FUTURES + "/marginType"), """
                            {"symbol": "%s", "marginType": "ISOLATED"}""".formatted(other)).andExpect(status().isOk());
            String id = id(as(trader, post(FUTURES + "/order"), """
                            {"symbol": "%s", "side": "BUY", "type": "LIMIT", "price": "50", "quantity": "578"}""".formatted(other))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("NEW")));

            // 40 long at 100, now 50 and stale: a loss of 2,000; the haircut 1 % of 2,000.
            staleMark("50");
            matching.fill(
                    new RestingOrder(
                            UUID.fromString(id),
                            trader,
                            MarketType.FUTURES,
                            otherPair,
                            OrderSide.BUY,
                            new BigDecimal("50"),
                            Instant.now().minusSeconds(1)),
                    Instant.now(),
                    FillSource.LIVE);

            assertThat(statusOf(id)).isEqualTo("EXPIRED");
            assertThat(sql.sql("select status_reason from trade_order where order_id = ?")
                            .param(UUID.fromString(id))
                            .query(String.class)
                            .single())
                    .isEqualTo("MARGIN_INSUFFICIENT");
        } finally {
            sql.sql("delete from futures_symbol_config where pair_id = ?")
                    .param(otherPair)
                    .update();
            sql.sql("delete from leverage_bracket where pair_id = ?")
                    .param(otherPair)
                    .update();
            sql.sql("delete from trade_order where pair_id = ?")
                    .param(otherPair)
                    .update();
            new MarketTestData(sql, Instant.now()).remove(otherPair);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Conditional (algo) orders
    // ------------------------------------------------------------------------------------------

    @Test
    void TR20_aConditionalOrder_waitsOnTheAlgoEndpoints_andOneThatWouldTriggerAtOnce_isRefused() throws Exception {
        UUID trader = trader(true);

        String id = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "STOP_MARKET",
                         "triggerPrice": "95", "quantity": "1", "workingType": "MARK_PRICE", "clientAlgoId": "sl-1"}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algoStatus").value("NEW"))
                .andExpect(jsonPath("$.algoType").value("CONDITIONAL"))
                .andExpect(jsonPath("$.workingType").value("MARK_PRICE"))
                .andExpect(jsonPath("$.clientAlgoId").value("sl-1")));

        as(trader, get(FUTURES + "/openAlgoOrders"), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].algoId").value(id));
        as(trader, get(FUTURES + "/openOrders"), null)
                .andExpect(jsonPath("$.length()").value(0));
        as(trader, get(FUTURES + "/algoOrder").param("symbol", symbol).param("clientAlgoId", "sl-1"), null)
                .andExpect(jsonPath("$.algoId").value(id));
        // The mark is at 100: a stop SELL at 105 is already met.
        algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "STOP_MARKET",
                         "triggerPrice": "105", "quantity": "1", "workingType": "MARK_PRICE"}""".formatted(symbol))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_ORDER_WOULD_TRIGGER"))
                .andExpect(jsonPath("$.binanceCode").value(-2021));
        algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "STOP_MARKET",
                         "triggerPrice": "95", "quantity": "1", "closePosition": true}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").value("MSG15"));
        algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "BUY", "type": "STOP",
                         "triggerPrice": "105", "quantity": "1"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG01"));
        algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "TRAILING_STOP_MARKET",
                         "triggerPrice": "95", "quantity": "1", "callbackRate": "1"}""".formatted(symbol)).andExpect(status().isBadRequest());
        // The last price is not known here: an order on it cannot be judged.
        algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "STOP_MARKET",
                         "triggerPrice": "95", "quantity": "1"}""".formatted(symbol)).andExpect(status().isServiceUnavailable());

        as(trader, delete(FUTURES + "/algoOrder").param("symbol", symbol).param("algoId", id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algoStatus").value("CANCELED"));
        as(trader, delete(FUTURES + "/algoOrder").param("symbol", symbol).param("algoId", id), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-2011));
    }

    @Test
    void TR20_aTriggeredStopLoss_closesThePosition_andFinishes() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        String id = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "STOP_MARKET",
                         "triggerPrice": "95", "closePosition": true, "workingType": "MARK_PRICE"}""".formatted(symbol)).andExpect(status().isOk()));

        depth(List.of(level("94.6", "5")), List.of(level("94.5", "5")));
        trigger(trader, id, OrderSide.SELL, TriggerCondition.AT_OR_BELOW, "95");

        assertThat(openPositions(trader)).isZero();
        String placed = JsonPath.read(
                as(trader, get(FUTURES + "/algoOrder").param("symbol", symbol).param("algoId", id), null)
                        .andExpect(jsonPath("$.algoStatus").value("FINISHED"))
                        .andExpect(jsonPath("$.actualPrice").value(numeric("94.5")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.actualOrderId");
        as(trader, get(FUTURES + "/order").param("symbol", symbol).param("orderId", placed), null)
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.reduceOnly").value(true));
        as(trader, get(FUTURES + "/allAlgoOrders").param("symbol", symbol), null)
                .andExpect(jsonPath("$[0].actualOrderId").value(placed));
    }

    @Test
    void TR20_aStopLimit_placesItsLimitOrder_whichWaits_andTheAlgoOrderIsTriggered() throws Exception {
        UUID trader = trader(true);
        String id = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "BUY", "type": "STOP",
                         "triggerPrice": "105", "price": "106", "quantity": "1", "workingType": "MARK_PRICE"}""".formatted(symbol)).andExpect(status().isOk()));

        depth(List.of(level("107", "5")), List.of(level("106.9", "5")));
        trigger(trader, id, OrderSide.BUY, TriggerCondition.AT_OR_ABOVE, "105");

        as(trader, get(FUTURES + "/algoOrder").param("symbol", symbol).param("algoId", id), null)
                .andExpect(jsonPath("$.algoStatus").value("TRIGGERED"));
        as(trader, get(FUTURES + "/openOrders").param("symbol", symbol), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].price").value(numeric("106")));
    }

    @Test
    void TR20_marginIsCheckedWhenItTriggers_notWhenItIsPlaced() throws Exception {
        UUID trader = trader(true);
        leverage(trader, 1);
        String id = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "BUY", "type": "STOP_MARKET",
                         "triggerPrice": "105", "quantity": "60", "workingType": "MARK_PRICE"}""".formatted(symbol)).andExpect(status().isOk()));

        depth(List.of(level("105", "100")), List.of(level("104.9", "100")));
        trigger(trader, id, OrderSide.BUY, TriggerCondition.AT_OR_ABOVE, "105");

        as(trader, get(FUTURES + "/algoOrder").param("symbol", symbol).param("algoId", id), null)
                .andExpect(jsonPath("$.algoStatus").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("MARGIN_INSUFFICIENT"));
        assertThat(openPositions(trader)).isZero();
    }

    @Test
    void TR20_closingConditionalOrders_endWithThePosition_andOnesWithNothingToClose_expire() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        String takeProfit = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "TAKE_PROFIT_MARKET",
                         "triggerPrice": "110", "closePosition": true, "workingType": "MARK_PRICE"}""".formatted(symbol)).andExpect(status().isOk()));
        String reduce = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "STOP_MARKET",
                         "triggerPrice": "90", "quantity": "1", "reduceOnly": true, "workingType": "MARK_PRICE"}""".formatted(symbol)).andExpect(status().isOk()));

        place(trader, market("SELL", "1")).andExpect(status().isOk());

        assertThat(statusOf(takeProfit)).isEqualTo("EXPIRED");
        assertThat(statusOf(reduce)).isEqualTo("EXPIRED");

        String orphan = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "STOP_MARKET",
                         "triggerPrice": "95", "closePosition": true, "workingType": "MARK_PRICE"}""".formatted(symbol)).andExpect(status().isOk()));
        trigger(trader, orphan, OrderSide.SELL, TriggerCondition.AT_OR_BELOW, "95");
        as(trader, get(FUTURES + "/algoOrder").param("symbol", symbol).param("algoId", orphan), null)
                .andExpect(jsonPath("$.algoStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.statusReason").value("NOTHING_TO_CLOSE"));
        as(trader, delete(FUTURES + "/algoOpenOrders").param("symbol", symbol), null)
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** With priceProtect, no last price to compare the mark with means it does not trigger and keeps waiting. */
    @Test
    void TR20_aProtectedOrder_withoutALastPriceToCompare_keepsWaiting() throws Exception {
        UUID trader = trader(true);
        String id = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "BUY", "type": "STOP_MARKET",
                         "triggerPrice": "105", "quantity": "1", "workingType": "MARK_PRICE", "priceProtect": true}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceProtect").value(true)));

        trigger(trader, id, OrderSide.BUY, TriggerCondition.AT_OR_ABOVE, "105");

        assertThat(statusOf(id)).isEqualTo("NEW");
    }

    /** Plan 5.6: an opening order's TP/SL is placed once it executed; one closing the position cancels the other. */
    @Test
    void TR20_aTakeProfitAndStopLoss_comeWithAnOpeningOrder_andOneCancelsTheOther() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));

        place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "MARKET", "quantity": "1", "takeProfitPrice": "110",
                         "stopLossPrice": "90", "tpSlWorkingType": "MARK_PRICE"}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FILLED"));

        String body = as(trader, get(FUTURES + "/openAlgoOrders"), null)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].closePosition").value(true))
                .andExpect(jsonPath("$[0].side").value("SELL"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> types = JsonPath.read(body, "$[*].orderType");
        List<String> ids = JsonPath.read(body, "$[*].algoId");
        String stopLoss = ids.get(types.indexOf("STOP_MARKET"));
        String takeProfit = ids.get(types.indexOf("TAKE_PROFIT_MARKET"));

        depth(List.of(level("90.1", "5")), List.of(level("90", "5")));
        trigger(trader, stopLoss, OrderSide.SELL, TriggerCondition.AT_OR_BELOW, "90");

        assertThat(openPositions(trader)).isZero();
        assertThat(statusOf(takeProfit)).isEqualTo("CANCELED");
        as(trader, get(FUTURES + "/openAlgoOrders"), null)
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void TR20_theTpSlOfAWaitingOrder_waitsWithIt_goesWithItsCancel_andIsCheckedForItsSide() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));

        place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "LIMIT", "price": "95", "quantity": "1",
                         "takeProfitPrice": "94"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.takeProfitPrice").value("MSG15"));
        place(trader, """
                        {"symbol": "%s", "side": "SELL", "type": "MARKET", "quantity": "1", "reduceOnly": true,
                         "stopLossPrice": "110"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reduceOnly").value("MSG15"));
        String id = id(place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "LIMIT", "price": "95", "quantity": "1",
                         "takeProfitPrice": "105", "stopLossPrice": "90"}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW")));

        as(trader, get(FUTURES + "/openAlgoOrders"), null)
                .andExpect(jsonPath("$.length()").value(0));
        as(trader, delete(FUTURES + "/order").param("symbol", symbol).param("orderId", id), null)
                .andExpect(status().isOk());

        assertThat(sql.sql("""
                                select count(*) from trade_order
                                 where user_id = ? and order_type in ('TAKE_PROFIT_MARKET', 'STOP_MARKET')
                                   and order_status = 'CANCELED'""").param(trader).query(Long.class).single()).isEqualTo(2);
    }

    /** A stop loss already met when its waiting order fills is not placed: it would trigger at once. */
    @Test
    void TR20_aStopLossAlreadyMetWhenItsOrderFills_isRejected() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        String id = id(place(trader, """
                        {"symbol": "%s", "side": "BUY", "type": "LIMIT", "price": "95", "quantity": "1",
                         "stopLossPrice": "94", "tpSlWorkingType": "MARK_PRICE"}""".formatted(symbol)).andExpect(status().isOk()));
        mark("93");

        matching.fill(
                new RestingOrder(
                        UUID.fromString(id),
                        trader,
                        MarketType.FUTURES,
                        pair,
                        OrderSide.BUY,
                        new BigDecimal("95"),
                        Instant.now().minusSeconds(1)),
                Instant.now(),
                FillSource.LIVE);

        assertThat(sql.sql("""
                                select order_status || ':' || status_reason from trade_order
                                 where user_id = ? and order_type = 'STOP_MARKET'""").param(trader).query(String.class).single()).isEqualTo("REJECTED:WOULD_TRIGGER");
    }

    /** TR-21: a trailing stop takes a callback of 0.1 to 20 % and an optional activation not yet reached. */
    @Test
    void TR21_aTrailingStop_isPlacedWithItsCallback_andTriggeredItClosesThePosition() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());

        String id = algoId(algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "TRAILING_STOP_MARKET",
                         "quantity": "1", "callbackRate": "1", "activatePrice": "105", "reduceOnly": true,
                         "workingType": "MARK_PRICE"}""".formatted(symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algoStatus").value("NEW"))
                .andExpect(jsonPath("$.activatePrice").value(numeric("105")))
                .andExpect(jsonPath("$.priceRate").value(numeric("1")))
                .andExpect(jsonPath("$.triggerPrice").doesNotExist()));
        algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "TRAILING_STOP_MARKET",
                         "quantity": "1", "callbackRate": "1", "activatePrice": "95", "workingType": "MARK_PRICE"}""".formatted(symbol))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.binanceCode").value(-2021));
        algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "TRAILING_STOP_MARKET",
                         "quantity": "1", "callbackRate": "25"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.callbackRate").value("MSG15"));
        algo(trader, """
                        {"algoType": "CONDITIONAL", "symbol": "%s", "side": "SELL", "type": "TRAILING_STOP_MARKET",
                         "quantity": "1"}""".formatted(symbol))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.callbackRate").value("MSG01"));

        // The engine found the pull-back: 1 % below a peak of 110.
        depth(List.of(level("109", "5")), List.of(level("108.9", "5")));
        trigger(trader, id, OrderSide.SELL, TriggerCondition.AT_OR_ABOVE, "108.9");

        assertThat(openPositions(trader)).isZero();
        as(trader, get(FUTURES + "/algoOrder").param("symbol", symbol).param("algoId", id), null)
                .andExpect(jsonPath("$.algoStatus").value("FINISHED"));
    }

    private ResultActions algo(UUID trader, String body) throws Exception {
        return as(trader, post(FUTURES + "/algoOrder"), body);
    }

    private static String algoId(ResultActions placed) throws Exception {
        return JsonPath.read(placed.andReturn().getResponse().getContentAsString(), "$.algoId");
    }

    /** The engine triggers the conditional order at its trigger price, as a mark or a trade reaching it does. */
    private void trigger(UUID trader, String id, OrderSide side, TriggerCondition condition, String price) {
        matching.trigger(
                new TriggerOrder(
                        UUID.fromString(id),
                        trader,
                        MarketType.FUTURES,
                        pair,
                        side,
                        condition,
                        new BigDecimal(price),
                        null,
                        null,
                        Instant.now().minusSeconds(1)),
                Instant.now(),
                new BigDecimal(price),
                FillSource.LIVE,
                null);
    }

    // ------------------------------------------------------------------------------------------
    // Liquidation of isolated positions
    // ------------------------------------------------------------------------------------------

    /**
     * Plan 4.12, example 1: Long 0.1 at 60,000, 10x, a wallet of 600, liquidated at 54,216.9: a loss of 578.31 and a
     * clearance fee of what is left, 21.69 (less than 1.25 % of the notional): the Trader loses the 600, no more.
     */
    @Test
    void TR22_anIsolatedLong_isLiquidatedAtItsPrice_andLosesExactlyItsWallet() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        mark("60000");
        depth(List.of(level("60000", "5")), List.of(level("59999.9", "5")));
        place(trader, market("BUY", "0.1")).andExpect(status().isOk());
        String waiting = id(place(trader, limit("BUY", "GTC", "55000", "0.1")).andExpect(status().isOk()));
        UUID positionId = (UUID) position(trader).get("position_id");

        mark("56000");
        assertThat(liquidate(trader, positionId, "56000")).isFalse();
        assertThat(liquidate(trader, positionId, "54216.9")).isTrue();

        Map<String, Object> closed = sql.sql("select * from futures_position where position_id = ?")
                .param(positionId)
                .query()
                .singleRow();
        assertThat(closed.get("close_reason")).isEqualTo("LIQUIDATION");
        assertThat((BigDecimal) closed.get("realized_pnl_amount")).isEqualByComparingTo("-578.31");
        assertThat((BigDecimal) closed.get("liquidation_fee_amount")).isEqualByComparingTo("21.69");
        assertThat(wallet(trader)).isEqualByComparingTo("4400");
        assertThat(ledgerSum(trader)).isEqualByComparingTo(wallet(trader));
        assertThat(sql.sql("""
                                select count(*) from wallet_ledger_entry
                                 where user_id = ? and entry_type = 'INSURANCE_COVER'""").param(trader).query(Long.class).single()).isZero();
        assertThat(statusOf(waiting)).isEqualTo("CANCELED");
        as(trader, get(FUTURES + "/forceOrders"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("FILLED"))
                .andExpect(jsonPath("$[0].avgPrice").value(numeric("54216.9")))
                .andExpect(jsonPath("$[0].reduceOnly").value(true));
        String clientId = JsonPath.read(
                as(trader, get(FUTURES + "/forceOrders").param("symbol", symbol), null)
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$[0].clientOrderId");
        assertThat(clientId).startsWith("autoclose-");
        as(trader, get(FUTURES + "/forceOrders").param("limit", "101"), null).andExpect(status().isBadRequest());
    }

    /** A mark past the bankruptcy price: the loss beyond the wallet is an insurance cover, no fee is left to take. */
    @Test
    void TR22_aMarkPastTheBankruptcyPrice_leavesTheCrossWalletWhole() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        mark("60000");
        depth(List.of(level("60000", "5")), List.of(level("59999.9", "5")));
        place(trader, market("BUY", "0.1")).andExpect(status().isOk());
        UUID positionId = (UUID) position(trader).get("position_id");

        assertThat(liquidate(trader, positionId, "50000")).isTrue();

        // Lost 1,000 on a wallet of 600: 400 covered.
        assertThat(wallet(trader)).isEqualByComparingTo("4400");
        assertThat(sql.sql("""
                                select amount from wallet_ledger_entry
                                 where user_id = ? and entry_type = 'INSURANCE_COVER'""").param(trader).query(BigDecimal.class).single()).isEqualByComparingTo("400");
        Map<String, Object> adjustment = sql.sql("""
                        select amount, ref_type from futures_margin_adjustment
                         where position_id = ? and adjustment_type = 'LIQUIDATION'""").param(positionId).query().singleRow();
        assertThat((BigDecimal) adjustment.get("amount")).isEqualByComparingTo("-600");
        assertThat(adjustment.get("ref_type")).isEqualTo("LEDGER");
        assertThat(liquidate(trader, positionId, "40000")).isFalse();
    }

    /** The engine liquidates a position the way the matching service does it for a mark that reached its level. */
    private boolean liquidate(UUID trader, UUID positionId, String mark) {
        return matching.liquidate(
                new LiquidationLevel(positionId, trader, pair, PositionDirection.LONG, new BigDecimal("54216.9")),
                new BigDecimal(mark),
                Instant.now());
    }

    // ------------------------------------------------------------------------------------------
    // Funding
    // ------------------------------------------------------------------------------------------

    /** A long pays a positive rate on its size at the settlement, once, however often the run goes. */
    @Test
    void TR24_aCrossLong_paysThePositiveRate_once() throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        backdate(trader, Duration.ofHours(2));
        settlement(Duration.ofHours(1), "0.0001", "100");

        assertThat(funding.run()).isEqualTo(1);
        assertThat(funding.run()).isZero();

        // -1 x 1 x 100 x 0.0001
        assertThat(wallet(trader)).isEqualByComparingTo("4999.99");
        assertThat(ledgerSum(trader)).isEqualByComparingTo(wallet(trader));
        assertThat((BigDecimal) position(trader).get("funding_amount")).isEqualByComparingTo("-0.01");
        assertThat(sql.sql("""
                                select ref_type from wallet_ledger_entry
                                 where user_id = ? and entry_type = 'FUNDING_FEE'""").param(trader).query(String.class).single()).isEqualTo("FUNDING");
    }

    /**
     * An isolated short takes funding in its wallet both ways (rule B): received, the wallet grows; paid beyond it, the
     * wallet goes to zero and the rest is an insurance cover, never the cross wallet.
     */
    @Test
    void TR24_anIsolatedShort_takesFundingInItsWallet_andAPaymentBeyondItIsCovered() throws Exception {
        UUID trader = trader(true);
        isolated(trader, 10);
        depth(List.of(level("100.1", "5")), List.of(level("100", "5")));
        place(trader, market("SELL", "2")).andExpect(status().isOk());
        UUID positionId = (UUID) position(trader).get("position_id");
        backdate(trader, Duration.ofHours(3));
        settlement(Duration.ofHours(2), "0.01", "100");
        settlement(Duration.ofHours(1), "-0.2", "100");

        assertThat(funding.run()).isEqualTo(2);

        // +2 into a wallet of 20, then -40 on 22: 18 covered.
        assertThat((BigDecimal) position(trader).get("isolated_wallet_amount")).isEqualByComparingTo("0");
        assertThat(wallet(trader)).isEqualByComparingTo("4980");
        assertThat(ledgerSum(trader)).isEqualByComparingTo(wallet(trader));
        assertThat(sql.sql("""
                                select string_agg(amount::text, ',' order by created_at, adjustment_id)
                                  from futures_margin_adjustment
                                 where position_id = ? and adjustment_type = 'FUNDING'""").param(positionId).query(String.class).single()).isEqualTo("2.00000000,-22.00000000");
    }

    /** A position opened after a settlement owes nothing of it. */
    @Test
    void TR24_aPositionOpenedAfterTheSettlement_owesNothing() throws Exception {
        UUID trader = trader(true);
        settlement(Duration.ofMinutes(30), "0.0001", "100");
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());

        assertThat(funding.run()).isZero();
        assertThat(wallet(trader)).isEqualByComparingTo("5000");
    }

    // ------------------------------------------------------------------------------------------
    // Income and position history
    // ------------------------------------------------------------------------------------------

    @Test
    void TR19_theIncome_namesThePairOfEachChange_inTheExchangesTypes_andTheClosedPositionIsInTheHistory()
            throws Exception {
        UUID trader = trader(true);
        depth(List.of(level("100", "5")), List.of(level("99.9", "5")));
        place(trader, market("BUY", "1")).andExpect(status().isOk());
        backdate(trader, Duration.ofHours(2));
        settlement(Duration.ofHours(1), "0.0001", "100");
        funding.run();
        depth(List.of(level("110.1", "5")), List.of(level("110", "5")));
        place(trader, market("SELL", "1")).andExpect(status().isOk());

        as(trader, get(FUTURES + "/income"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].incomeType").value("TRANSFER"))
                .andExpect(jsonPath("$[0].symbol").value(""))
                .andExpect(jsonPath("$[0].income").value(numeric("5000")))
                .andExpect(jsonPath("$[1].incomeType").value("FUNDING_FEE"))
                .andExpect(jsonPath("$[1].symbol").value(symbol))
                .andExpect(jsonPath("$[1].income").value(numeric("-0.01")))
                .andExpect(jsonPath("$[2].incomeType").value("REALIZED_PNL"))
                .andExpect(jsonPath("$[2].income").value(numeric("10")))
                .andExpect(jsonPath("$[2].asset").value("USDT"));
        as(trader, get(FUTURES + "/income").param("incomeType", "REALIZED_PNL"), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tradeId").isNotEmpty());
        as(trader, get(FUTURES + "/income").param("symbol", symbol).param("limit", "1"), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].incomeType").value("REALIZED_PNL"));
        as(trader, get(FUTURES + "/income").param("incomeType", "BONUS"), null).andExpect(status().isBadRequest());

        as(trader, get(FUTURES + "/positionHistory"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value(symbol))
                .andExpect(jsonPath("$[0].direction").value("LONG"))
                .andExpect(jsonPath("$[0].closeReason").value("MANUAL"))
                .andExpect(jsonPath("$[0].avgClosePrice").value(numeric("110")))
                .andExpect(jsonPath("$[0].realizedPnl").value(numeric("10")))
                .andExpect(jsonPath("$[0].fundingFee").value(numeric("-0.01")))
                .andExpect(jsonPath("$[0].netProfit").value(numeric("9.99")));
        as(trader, get(FUTURES + "/positionHistory").param("symbol", symbol).param("limit", "0"), null)
                .andExpect(status().isBadRequest());
    }

    /** Moves the Trader's positions and fills back in time, as if opened then. */
    private void backdate(UUID trader, Duration ago) {
        Timestamp then = Timestamp.from(Instant.now().minus(ago));
        sql.sql("update futures_position set opened_at = ? where user_id = ?")
                .params(then, trader)
                .update();
        sql.sql("update order_fill set traded_at = ? where user_id = ?")
                .params(then, trader)
                .update();
    }

    /** A funding settlement of the test pair, stored as the market stores it. */
    private void settlement(Duration ago, String rate, String mark) {
        sql.sql("""
                        insert into funding_rate_history (pair_id, funding_time, funding_rate, mark_price)
                        values (?, ?, ?, ?)""")
                .params(pair, Timestamp.from(Instant.now().minus(ago)), new BigDecimal(rate), new BigDecimal(mark))
                .update();
    }

    /** A mark the exchange produced longer ago than mark-max-age (5 s): the cache holds it, but it is not current. */
    private void staleMark(String mark) {
        redis.delete("px:FUTURES:" + symbol);
        BigDecimal value = new BigDecimal(mark);
        prices.record(
                MarketType.FUTURES,
                new MarkPriceMessage(
                        symbol,
                        value,
                        value,
                        BigDecimal.ZERO,
                        Instant.now().plus(Duration.ofHours(1)),
                        Instant.now().minusSeconds(30)));
        prices.flush();
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private String market(String side, String quantity) {
        return """
                {"symbol": "%s", "side": "%s", "type": "MARKET", "quantity": "%s"}""".formatted(symbol, side, quantity);
    }

    private String limit(String side, String timeInForce, String price, String quantity) {
        return """
                {"symbol": "%s", "side": "%s", "type": "LIMIT", "timeInForce": "%s", "price": "%s",
                 "quantity": "%s"}""".formatted(symbol, side, timeInForce, price, quantity);
    }

    private ResultActions place(UUID trader, String body) throws Exception {
        return as(trader, post(FUTURES + "/order"), body);
    }

    private ResultActions positionMargin(UUID trader, String amount, int type) throws Exception {
        return as(trader, post(FUTURES + "/positionMargin"), """
                {"symbol": "%s", "amount": "%s", "type": %d}""".formatted(symbol, amount, type));
    }

    private ResultActions close(UUID trader, String body) throws Exception {
        return as(trader, post(FUTURES + "/closePosition"), body);
    }

    private void leverage(UUID trader, int leverage) throws Exception {
        leverage(trader, leverage, status().isOk());
    }

    private void leverage(UUID trader, int leverage, ResultMatcher expected) throws Exception {
        as(trader, post(FUTURES + "/leverage"), """
                        {"symbol": "%s", "leverage": %d}""".formatted(symbol, leverage)).andExpect(expected);
    }

    private void isolated(UUID trader, int leverage) throws Exception {
        leverage(trader, leverage);
        as(trader, post(FUTURES + "/marginType"), """
                        {"symbol": "%s", "marginType": "ISOLATED"}""".formatted(symbol)).andExpect(status().isOk());
    }

    private static String id(ResultActions placed) throws Exception {
        String body = placed.andReturn().getResponse().getContentAsString();
        return body.contains("\"orderId\"") ? JsonPath.read(body, "$.orderId") : null;
    }

    private Map<String, Object> position(UUID trader) {
        return sql.sql("select * from futures_position where user_id = ? and position_status = 'OPEN'")
                .param(trader)
                .query()
                .singleRow();
    }

    private long openPositions(UUID trader) {
        return sql.sql("select count(*) from futures_position where user_id = ? and position_status = 'OPEN'")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private String statusOf(String orderId) {
        return sql.sql("select order_status from trade_order where order_id = ?")
                .param(UUID.fromString(orderId))
                .query(String.class)
                .single();
    }

    private long orderCount(UUID trader) {
        return sql.sql("select count(*) from trade_order where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private BigDecimal wallet(UUID trader) {
        return sql.sql("""
                        select b.free_amount from wallet_balance b join coin c on c.coin_id = b.coin_id
                         where b.user_id = ? and b.wallet_type = 'FUTURES' and c.symbol = 'USDT'""").param(trader).query(BigDecimal.class).single();
    }

    private BigDecimal ledgerSum(UUID trader) {
        return sql.sql("select sum(amount) from wallet_ledger_entry where user_id = ? and wallet_type = 'FUTURES'")
                .param(trader)
                .query(BigDecimal.class)
                .single();
    }

    private void bracket(int number, String floor, String cap, int leverage, String mmr, String cum) {
        sql.sql("""
                        insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                      maintenance_margin_rate, maintenance_amount, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        pair,
                        number,
                        new BigDecimal(floor),
                        new BigDecimal(cap),
                        leverage,
                        new BigDecimal(mmr),
                        new BigDecimal(cum),
                        Timestamp.from(Instant.now()))
                .update();
    }

    /** Streams a Futures mark price of the test pair into the price cache, newer than every earlier one. */
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

    /** A Futures depth20 snapshot of the test pair, newer than every earlier one. */
    private void depth(List<PriceLevel> asks, List<PriceLevel> bids) {
        trading.recordDepth(
                new OrderBookDepth(MarketType.FUTURES, pair, TICKS.incrementAndGet(), bids, asks, Instant.now()));
    }

    /** A decimal of a response, written as a string (D-51), equal in value to {@code expected}. */
    private static Matcher<Object> numeric(String expected) {
        return new CustomMatcher<>("a decimal equal to " + expected) {
            @Override
            public boolean matches(Object actual) {
                return actual != null && new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected)) == 0;
            }
        };
    }

    private static PriceLevel level(String price, String quantity) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(quantity));
    }

    /** A Trader with an opened paper account, on the PRO plan when {@code futures}. */
    private UUID trader(boolean futures) throws Exception {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@tr15.invalid", now, now).update();
        traders.add(id);
        if (futures) {
            subscribeToPro(id);
        }
        as(id, post("/api/v1/paper/account"), null).andExpect(status().isCreated());
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
                        "TR15-" + order,
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
