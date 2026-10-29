package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Paper Futures orders end to end, on the database: placement with the exchange's refusals and codes, execution on
 * arrival against the Futures book, a waiting order filled by the engine, and what each fill does to the one-way
 * position, the Futures wallet, the isolated wallet and its audit rows; then the positions and the account read at
 * the mark, and the transfer out limited by what can be withdrawn.
 *
 * <p>The Futures wallet is opened with 5,000 USDT; fees are zero.
 *
 * <p>Rule: TR-15, TR-16, TR-18; D-127; Q-T10.
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

    private void leverage(UUID trader, int leverage) throws Exception {
        as(trader, post(FUTURES + "/leverage"), """
                        {"symbol": "%s", "leverage": %d}""".formatted(symbol, leverage)).andExpect(status().isOk());
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
