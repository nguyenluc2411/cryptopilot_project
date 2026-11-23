package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.MarketIncidents;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * M1, the gate of a paused market (D-159): every request that changes a paper order, position or wallet of a market is
 * refused with {@code TRADING_PAUSED} (503, MSG57, naming the market) while that market is closed, cancels and amends
 * included, before the order or the position is even looked up; the same request is never refused for the other
 * market's pause; and the few requests that need no price (a transfer into Futures, the risk warnings of an order form)
 * pass whichever market is closed.
 *
 * <p>Markets are closed through maintenance, which closes them as an outage does, with the health monitor off.
 *
 * <p>Rule: NSF-03; D-159.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TradingPauseGateApiTest {

    private static final String PAPER = "/api/v1/paper";
    private static final String FUTURES = PAPER + "/futures";
    private static final Set<MarketType> SPOT = EnumSet.of(MarketType.SPOT);
    private static final Set<MarketType> FUTURES_ONLY = EnumSet.of(MarketType.FUTURES);
    private static final Set<MarketType> NEVER = EnumSet.noneOf(MarketType.class);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MarketHealthService health;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;
    private String symbol;

    /** One request of the matrix: its verb, path, query and body, with {pair}, {symbol} and {id} filled in. */
    record Call(
            String name, String method, String path, Map<String, String> params, String body, Set<MarketType> gatedBy) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Call> calls() {
        return Stream.of(
                // Spot
                new Call("spot MARKET order", "POST", PAPER + "/orders", Map.of(), """
                        {"pairId": "{pair}", "market": "SPOT", "side": "BUY", "type": "MARKET", "quantity": 0.01}""", SPOT),
                new Call("spot LIMIT order", "POST", PAPER + "/orders", Map.of(), """
                        {"pairId": "{pair}", "market": "SPOT", "side": "BUY", "type": "LIMIT", "price": 90,
                         "quantity": 1, "timeInForce": "GTC"}""", SPOT),
                new Call("spot STOP_LOSS_LIMIT order", "POST", PAPER + "/orders", Map.of(), """
                        {"pairId": "{pair}", "market": "SPOT", "side": "SELL", "type": "STOP_LOSS_LIMIT",
                         "stopPrice": 90, "price": 89, "quantity": 1, "timeInForce": "GTC"}""", SPOT),
                new Call("spot OCO list", "POST", PAPER + "/order-lists", Map.of(), """
                        {"pairId": "{pair}", "market": "SPOT", "type": "OCO", "oco": {"side": "SELL", "quantity": 1,
                         "takeProfitPrice": 110, "stopPrice": 90, "stopLimitPrice": 89}}""", SPOT),
                new Call("spot cancel order", "POST", PAPER + "/orders/{id}/cancel", Map.of(), null, SPOT),
                new Call("spot replace order", "POST", PAPER + "/orders/{id}/replace", Map.of(), """
                        {"price": 85}""", SPOT),
                new Call("spot cancel list", "POST", PAPER + "/order-lists/{id}/cancel", Map.of(), null, SPOT),
                new Call(
                        "spot cancel all open",
                        "DELETE",
                        PAPER + "/orders/open",
                        Map.of("pairId", "{pair}"),
                        null,
                        SPOT),
                // Futures orders
                new Call("futures MARKET order", "POST", FUTURES + "/order", Map.of(), """
                        {"symbol": "{symbol}", "side": "BUY", "type": "MARKET", "quantity": "1"}""", FUTURES_ONLY),
                new Call("futures LIMIT order", "POST", FUTURES + "/order", Map.of(), """
                        {"symbol": "{symbol}", "side": "BUY", "type": "LIMIT", "timeInForce": "GTC", "price": "90",
                         "quantity": "1"}""", FUTURES_ONLY),
                new Call("futures amend", "PUT", FUTURES + "/order", Map.of(), """
                        {"symbol": "{symbol}", "orderId": "{id}", "side": "BUY", "price": "91", "quantity": "1"}""", FUTURES_ONLY),
                new Call(
                        "futures cancel",
                        "DELETE",
                        FUTURES + "/order",
                        Map.of("symbol", "{symbol}", "orderId", "{id}"),
                        null,
                        FUTURES_ONLY),
                new Call(
                        "futures cancel all",
                        "DELETE",
                        FUTURES + "/allOpenOrders",
                        Map.of("symbol", "{symbol}"),
                        null,
                        FUTURES_ONLY),
                new Call("futures conditional order", "POST", FUTURES + "/algoOrder", Map.of(), """
                        {"algoType": "CONDITIONAL", "symbol": "{symbol}", "side": "SELL", "type": "STOP_MARKET",
                         "triggerPrice": "95", "quantity": "1", "workingType": "MARK_PRICE"}""", FUTURES_ONLY),
                new Call(
                        "futures cancel conditional",
                        "DELETE",
                        FUTURES + "/algoOrder",
                        Map.of("symbol", "{symbol}", "algoId", "{id}"),
                        null,
                        FUTURES_ONLY),
                new Call(
                        "futures cancel all conditional",
                        "DELETE",
                        FUTURES + "/algoOpenOrders",
                        Map.of("symbol", "{symbol}"),
                        null,
                        FUTURES_ONLY),
                // Futures positions and settings
                new Call("futures close position", "POST", FUTURES + "/closePosition", Map.of(), """
                        {"symbol": "{symbol}", "type": "MARKET", "percent": 100}""", FUTURES_ONLY),
                new Call("futures position margin", "POST", FUTURES + "/positionMargin", Map.of(), """
                        {"symbol": "{symbol}", "amount": "10", "type": 1}""", FUTURES_ONLY),
                new Call("futures leverage", "POST", FUTURES + "/leverage", Map.of(), """
                        {"symbol": "{symbol}", "leverage": 10}""", FUTURES_ONLY),
                new Call("futures margin type", "POST", FUTURES + "/marginType", Map.of(), """
                        {"symbol": "{symbol}", "marginType": "ISOLATED"}""", FUTURES_ONLY),
                new Call("futures position mode", "POST", FUTURES + "/positionSide/dual", Map.of(), """
                        {"dualSidePosition": true}""", FUTURES_ONLY),
                // Wallets
                new Call("transfer out of Futures", "POST", PAPER + "/transfers", Map.of(), """
                        {"asset": "USDT", "from": "FUTURES", "amount": 1}""", FUTURES_ONLY),
                new Call("transfer into Futures", "POST", PAPER + "/transfers", Map.of(), """
                        {"asset": "USDT", "from": "SPOT", "amount": 1}""", NEVER),
                // Reads that need no gate
                new Call("futures order form warnings", "POST", FUTURES + "/order/warnings", Map.of(), """
                        {"symbol": "{symbol}", "side": "BUY", "type": "LIMIT", "timeInForce": "GTC", "price": "90",
                         "quantity": "1"}""", NEVER));
    }

    @BeforeEach
    void insertPair() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "PG" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
    }

    @AfterEach
    void reopenAndRemoveRows() {
        health.endMaintenance(MarketType.SPOT);
        health.endMaintenance(MarketType.FUTURES);
        MarketIncidents.removeAll(sql);
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
            // Paper notices (TR-29) and their pushes are written after the commit: awaited first.
            InAppNotifications.remove(sql, List.of(trader));
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
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

    /**
     * Each request: refused while its own market is closed, with the market named; never refused for the other
     * market's pause; and a request no market gates passes with both closed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("calls")
    void D159_aPausedMarket_refusesWhatChangesItsOrdersPositionsAndWallets(Call call) throws Exception {
        UUID trader = trader();

        for (MarketType market : call.gatedBy()) {
            health.startMaintenance(market, null, null);
            try {
                MvcResult refused = send(trader, call);
                assertThat(refused.getResponse().getStatus())
                        .as("%s while %s is paused", call, market)
                        .isEqualTo(503);
                String body = refused.getResponse().getContentAsString();
                assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("TRADING_PAUSED");
                assertThat((String) JsonPath.read(body, "$.messageCode")).isEqualTo("MSG57");
                assertThat((String) JsonPath.read(body, "$.market")).isEqualTo(market.name());
                assertThat((String) JsonPath.read(body, "$.state")).isEqualTo("MAINTENANCE");
            } finally {
                health.endMaintenance(market);
            }
        }

        Set<MarketType> others = call.gatedBy().isEmpty()
                ? EnumSet.allOf(MarketType.class)
                : EnumSet.complementOf(EnumSet.copyOf(call.gatedBy()));
        for (MarketType market : others) {
            health.startMaintenance(market, null, null);
        }
        try {
            String passed = send(trader, call).getResponse().getContentAsString();
            assertThat(passed).as("%s while %s is paused", call, others).doesNotContain("TRADING_PAUSED");
        } finally {
            for (MarketType market : others) {
                health.endMaintenance(market);
            }
        }
    }

    /**
     * The write endpoints of paper trading and of plans that need no gate, each with why: they change no order,
     * position or wallet, or a draft only.
     */
    private static final Set<String> UNGATED = Set.of(
            // Opening the paper account grants its starting balances, at no price.
            "POST /api/v1/paper/account",
            // The calculators and the order form's warnings compute; they store nothing.
            "POST /api/v1/paper/futures/order/warnings",
            "POST /api/v1/paper/futures/calculator/pnl",
            "POST /api/v1/paper/futures/calculator/target",
            "POST /api/v1/paper/futures/calculator/liquidation",
            "POST /api/v1/paper/futures/calculator/maxOpen",
            "POST /api/v1/paper/futures/calculator/openPrice",
            "POST /api/v1/plans/calculate",
            // A plan saved active, activated, cancelled while it waits, closed or given new levels is gated by its
            // market (PlanPauseGateApiTest); saved or edited as a draft it is not.
            "POST /api/v1/plans",
            "PUT /api/v1/plans/{id}");

    /** The plan endpoints gated by the plan's market, proven in PlanPauseGateApiTest. */
    private static final Set<String> PLANS_GATED = Set.of(
            "POST /api/v1/plans/{id}/activate",
            "POST /api/v1/plans/{id}/cancel",
            "POST /api/v1/plans/{id}/close-position",
            "PATCH /api/v1/plans/{id}/position-levels");

    /**
     * The safety net of the gate: every write endpoint of the paper and plan controllers is in this matrix, in the
     * plans' matrix, or named as needing no gate. A new endpoint fails here until it is placed in one of them.
     */
    @Test
    void D159_everyWriteEndpointOfTrading_isGatedOrNamedUngated() {
        Set<String> written = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            String module = handler.getBeanType().getPackageName();
            if (!module.equals("com.cryptopilot.paper.controller")
                    && !module.equals("com.cryptopilot.trading.controller")) {
                return;
            }
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (method == RequestMethod.GET || method == RequestMethod.HEAD || method == RequestMethod.OPTIONS) {
                    continue;
                }
                for (String path : info.getPatternValues()) {
                    written.add(method + " " + path);
                }
            }
        });
        Set<String> placed = new TreeSet<>(
                calls().map(call -> call.method() + " " + call.path().replace("{id}/", "{" + idName(call) + "}/"))
                        .collect(Collectors.toSet()));
        placed.addAll(UNGATED);
        placed.addAll(PLANS_GATED);

        assertThat(written)
                .as("write endpoints neither gated nor named ungated")
                .isSubsetOf(placed);
        assertThat(placed).as("entries that name no endpoint").isSubsetOf(written);
    }

    /** The path variable the controller names for the id of a cancel. */
    private static String idName(Call call) {
        return call.path().contains("order-lists") ? "listId" : "orderId";
    }

    private MvcResult send(UUID trader, Call call) throws Exception {
        String id = UUID.randomUUID().toString();
        String path = fill(call.path(), id);
        MockHttpServletRequestBuilder request =
                switch (call.method()) {
                    case "POST" -> post(path);
                    case "PUT" -> put(path);
                    case "DELETE" -> delete(path);
                    default -> throw new IllegalArgumentException(call.method());
                };
        call.params().forEach((name, value) -> request.param(name, fill(value, id)));
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader));
        if (call.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(fill(call.body(), id));
        }
        return mvc.perform(request).andReturn();
    }

    private String fill(String template, String id) {
        return template.replace("{pair}", pair.toString())
                .replace("{symbol}", symbol)
                .replace("{id}", id);
    }

    private UUID trader() throws Exception {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@pause.invalid", now, now).update();
        traders.add(id);
        mvc.perform(post(PAPER + "/account").header(HttpHeaders.AUTHORIZATION, bearer(id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$").exists());
        return id;
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
