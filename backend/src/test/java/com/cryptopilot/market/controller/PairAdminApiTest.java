package com.cryptopilot.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.ExchangeSymbol;
import com.cryptopilot.support.InAppNotifications;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SCR-37 and SCR-38: the pairs an administrator adds, switches and orders, BR-59 on the way out of service, and the
 * leverage brackets with their checks. The exchange is a mock, so adding a pair reads a fixed list.
 *
 * <p>Rule: UC-45, UC-46; BR-07, BR-26, BR-27, BR-57, BR-59.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PairAdminApiTest {

    private static final String PAIRS = "/api/v1/admin/pairs";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MarketApi marketApi;

    @MockitoBean
    private BinanceRestClient exchange;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private MarketTestData market;
    private String base;
    private UUID admin;
    private UUID trader;

    @BeforeEach
    void setUp() {
        market = new MarketTestData(sql, Instant.now());
        base = "PA" + Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
        admin = account(UserRole.ADMIN);
        trader = account(UserRole.TRADER);
    }

    @AfterEach
    void removeRows() {
        for (UUID pair : pairs) {
            sql.sql("delete from trading_journal where pair_id = ?").param(pair).update();
            sql.sql("delete from trading_plan where pair_id = ?").param(pair).update();
            sql.sql("delete from leverage_bracket where pair_id = ?")
                    .param(pair)
                    .update();
        }
        sql.sql("delete from audit_log where user_id = ?").param(admin).update();
        InAppNotifications.remove(sql, accounts);
        for (UUID pair : pairs) {
            market.remove(pair);
        }
        sql.sql("delete from crypto_pair where symbol like ?").param(base + "%").update();
        sql.sql("delete from coin where symbol = ? and not exists (select 1 from crypto_pair p"
                        + " where p.base_coin_id = coin.coin_id or p.quote_coin_id = coin.coin_id)")
                .param(base)
                .update();
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC45_theListIsForAdministrators_andShowsTheExchangeStatus() throws Exception {
        UUID pair = pair(false, false, "TRADING", "NOT_TRADING");

        as(admin, get(PAIRS + "?q=" + base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pairId").value(pair.toString()))
                .andExpect(jsonPath("$[0].status").value("INACTIVE"))
                .andExpect(jsonPath("$[0].spot.exchangeStatus").value("TRADING"))
                .andExpect(jsonPath("$[0].futures.exchangeStatus").value("NOT_TRADING"))
                .andExpect(jsonPath("$[0].bracketCount").value(0));
        as(trader, get(PAIRS)).andExpect(status().isForbidden());
        mvc.perform(get(PAIRS)).andExpect(status().isUnauthorized());
    }

    @Test
    void UC45_anAdministratorSwitchesAMarketOn_setsTheLeverage_andIsAudited() throws Exception {
        UUID pair = pair(false, false, "TRADING", "TRADING");
        filters(pair);

        update(pair, "ACTIVE", true, true, null, 3)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxLeverage").value("MSG01"));
        update(pair, "ACTIVE", true, true, 50, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.spotEnabled").value(true))
                .andExpect(jsonPath("$.futuresEnabled").value(true))
                .andExpect(jsonPath("$.maxLeverage").value(50))
                .andExpect(jsonPath("$.displayOrder").value(3));

        assertThat(sql.sql("select count(*) from audit_log where user_id = ? and action_code = 'CONFIGURATION_CHANGED'"
                                + " and entity_type = 'CRYPTO_PAIR' and entity_id = ?")
                        .params(admin, pair)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        update(pair, "ACTIVE", true, false, 50, 3)
                .andExpect(jsonPath("$.futuresEnabled").value(false))
                .andExpect(jsonPath("$.maxLeverage").doesNotExist());
    }

    @Test
    void UC45_aMarketTheExchangeDoesNotTrade_cannotBeSwitchedOn() throws Exception {
        UUID pair = pair(false, false, "TRADING", "NOT_TRADING");
        filters(pair);

        update(pair, "ACTIVE", false, true, 20, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAIR_NOT_TRADING"));
        update(pair, "ACTIVE", true, false, null, 1).andExpect(status().isOk());
    }

    @Test
    void BR59_aMarketWithAnOpenPosition_cannotBeDisabled_andNothingChanges() throws Exception {
        UUID pair = pair(true, false, "TRADING", null);
        filters(pair);
        openPosition(pair);

        update(pair, "ACTIVE", false, false, null, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAIR_HAS_OPEN_POSITIONS"));
        update(pair, "INACTIVE", true, false, null, 1).andExpect(status().isConflict());
        as(admin, get(PAIRS + "/" + pair))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.spotEnabled").value(true));
    }

    @Test
    void BR59_disablingAMarket_cancelsItsActivePlans_only() throws Exception {
        UUID pair = pair(true, false, "TRADING", null);
        filters(pair);
        UUID active = plan(pair, "ACTIVE");
        UUID draft = plan(pair, "DRAFT");

        update(pair, "INACTIVE", true, false, null, 1).andExpect(status().isOk());

        assertThat(planStatus(active)).isEqualTo("CANCELLED");
        assertThat(planStatus(draft)).isEqualTo("DRAFT");
        // The owner is told, once the change has committed.
        await().atMost(Duration.ofSeconds(10)).until(() -> notificationsOf(trader) > 0);
        assertThat(sql.sql("select notification_type || ':' || reference_id from notification where user_id = ?")
                        .param(trader)
                        .query(String.class)
                        .list())
                .containsExactly("PLAN:" + active);
        assertThat(marketApi.tradablePair(pair, com.cryptopilot.market.model.enums.MarketType.SPOT))
                .isEmpty();
    }

    @Test
    void UC46_brackets_areStoredWithTheMaintenanceAmountsOfBr27_andServeThePlans() throws Exception {
        UUID pair = pair(false, true, null, "TRADING");

        as(admin, put(PAIRS + "/" + pair + "/brackets"), """
                        {"brackets": [
                          {"notionalFloor": 0, "notionalCap": 50000, "maxLeverage": 125, "maintenanceMarginRate": 0.004},
                          {"notionalFloor": 50000, "notionalCap": 500000, "maxLeverage": 100,
                           "maintenanceMarginRate": 0.005, "maintenanceAmount": 50},
                          {"notionalFloor": 500000, "notionalCap": 8000000, "maxLeverage": 50,
                           "maintenanceMarginRate": 0.01}
                        ]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brackets[0].maintenanceAmount").value("0.00000000"))
                .andExpect(jsonPath("$.brackets[1].maintenanceAmount").value("50.00000000"))
                .andExpect(jsonPath("$.brackets[2].maintenanceAmount").value("2550.00000000"))
                .andExpect(jsonPath("$.brackets[2].bracketNo").value(3));

        List<LeverageTier> tiers = marketApi.leverageBrackets(pair);
        assertThat(tiers).hasSize(3);
        assertThat(tiers.get(2).maintenanceAmount()).isEqualByComparingTo(new BigDecimal("2550"));
        as(admin, get(PAIRS + "?q=" + base))
                .andExpect(jsonPath("$[0].bracketCount").value(3));
        assertThat(sql.sql("select count(*) from audit_log where user_id = ? and entity_type = 'LEVERAGE_BRACKET'")
                        .param(admin)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void UC46_bracketsWithAGap_aRisingLeverage_orAWrongAmount_areRefusedRowByRow() throws Exception {
        UUID pair = pair(false, true, null, "TRADING");

        as(admin, put(PAIRS + "/" + pair + "/brackets"), """
                        {"brackets": [
                          {"notionalFloor": 10, "notionalCap": 50000, "maxLeverage": 50, "maintenanceMarginRate": 0.004},
                          {"notionalFloor": 60000, "notionalCap": 40000, "maxLeverage": 75,
                           "maintenanceMarginRate": 0.005, "maintenanceAmount": 1}
                        ]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['brackets[0].notionalFloor']").value("MSG15"))
                .andExpect(jsonPath("$.errors['brackets[1].notionalFloor']").value("MSG15"))
                .andExpect(jsonPath("$.errors['brackets[1].notionalCap']").value("MSG15"))
                .andExpect(jsonPath("$.errors['brackets[1].maxLeverage']").value("MSG15"))
                .andExpect(jsonPath("$.errors['brackets[1].maintenanceAmount']").value("MSG15"));
        as(admin, put(PAIRS + "/" + pair + "/brackets"), """
                        {"brackets": [{"notionalFloor": 0, "notionalCap": 100, "maxLeverage": 20,
                                       "maintenanceMarginRate": 1}]}""").andExpect(status().isBadRequest());
        assertThat(marketApi.leverageBrackets(pair)).isEmpty();
    }

    /** BR-26: the pair's leverage ceiling stays within its first bracket, whichever of the two is changed. */
    @Test
    void BR26_thePairsMaximumLeverage_neverExceedsItsFirstBracket() throws Exception {
        UUID pair = pair(false, false, "TRADING", "TRADING");
        filters(pair);
        as(admin, put(PAIRS + "/" + pair + "/brackets"), """
                        {"brackets": [{"notionalFloor": 0, "notionalCap": 50000, "maxLeverage": 50,
                                       "maintenanceMarginRate": 0.01}]}""").andExpect(status().isOk());

        update(pair, "ACTIVE", false, true, 75, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxLeverage").value("MSG15"));
        update(pair, "ACTIVE", false, true, 50, 1).andExpect(status().isOk());
        as(admin, put(PAIRS + "/" + pair + "/brackets"), """
                        {"brackets": [{"notionalFloor": 0, "notionalCap": 50000, "maxLeverage": 20,
                                       "maintenanceMarginRate": 0.01}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['brackets[0].maxLeverage']").value("MSG15"));
        assertThat(marketApi.leverageBrackets(pair).getFirst().maxLeverage()).isEqualTo(50);
    }

    @Test
    void UC46_aPairNeverListedOnFutures_hasNoBrackets() throws Exception {
        UUID pair = pair(true, false, "TRADING", null);

        as(admin, put(PAIRS + "/" + pair + "/brackets"), """
                        {"brackets": [{"notionalFloor": 0, "notionalCap": 100, "maxLeverage": 20,
                                       "maintenanceMarginRate": 0.01}]}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAIR_NOT_TRADING"));
    }

    @Test
    void UC45_aSymbolOfTheExchange_isAddedInactive_withItsFilters_andOnlyOnce() throws Exception {
        String symbol = base + "USDT";
        when(exchange.spotExchangeInfo()).thenReturn(List.of(listed(symbol, null)));
        when(exchange.futuresExchangeInfo()).thenReturn(List.of(listed(symbol, "PERPETUAL")));

        as(admin, get(PAIRS + "/exchange-symbols?q=" + base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value(symbol))
                .andExpect(jsonPath("$[0].spotTrading").value(true))
                .andExpect(jsonPath("$[0].futuresTrading").value(true));
        as(admin, post(PAIRS), "{\"symbol\": \"" + symbol.toLowerCase() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value(symbol))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.baseAsset").value(base))
                .andExpect(jsonPath("$.spot.tickSize").value(0.01))
                .andExpect(jsonPath("$.futures.exchangeStatus").value("TRADING"));
        as(admin, post(PAIRS), "{\"symbol\": \"" + symbol + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAIR_ALREADY_REGISTERED"));
        as(admin, get(PAIRS + "/exchange-symbols?q=" + base))
                .andExpect(jsonPath("$.length()").value(0));
        as(admin, post(PAIRS), "{\"symbol\": \"" + base + "XYZ\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAIR_NOT_TRADING"));
    }

    // ------------------------------------------------------------------------------------------

    private ResultActions update(UUID pair, String status, boolean spot, boolean futures, Integer leverage, int order)
            throws Exception {
        return as(admin, put(PAIRS + "/" + pair), """
                {"status": "%s", "spotEnabled": %s, "futuresEnabled": %s, "maxLeverage": %s, "displayOrder": %d}""".formatted(status, spot, futures, leverage, order));
    }

    private static ExchangeSymbol listed(String symbol, String contractType) {
        return new ExchangeSymbol(
                symbol,
                ExchangeSymbol.TRADING,
                symbol.replace("USDT", ""),
                "USDT",
                contractType,
                new BigDecimal("0.01"),
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("5"));
    }

    private UUID pair(boolean spot, boolean futures, String spotStatus, String futuresStatus) {
        UUID id = market.pair(base + "USDT", spot, futures, spotStatus, futuresStatus, 0);
        pairs.add(id);
        return id;
    }

    private void filters(UUID pair) {
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5,
                               futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 5
                         where pair_id = ?""").param(pair).update();
    }

    private UUID plan(UUID pair, String status) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  stop_loss_price, take_profit_price, capital_amount, risk_percent,
                                                  plan_status, activated_at, executed_at, fill_price, created_at,
                                                  updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'MARKET', 90, 120, 1000, 1, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        trader,
                        pair,
                        status,
                        status.equals("DRAFT") ? null : now,
                        status.equals("EXECUTED") ? now : null,
                        status.equals("EXECUTED") ? new BigDecimal("100") : null,
                        now,
                        now)
                .update();
        return id;
    }

    private void openPosition(UUID pair) {
        UUID plan = plan(pair, "EXECUTED");
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, plan_id, source, market_type,
                                                     direction, entry_price, quantity, entry_time, trade_status,
                                                     created_at, updated_at)
                        values (?, ?, ?, ?, 'SIMULATED', 'SPOT', 'LONG', 100, 1, ?, 'OPEN', ?, ?)""")
                .params(UUID.randomUUID(), trader, pair, plan, now, now, now)
                .update();
    }

    private long notificationsOf(UUID user) {
        return sql.sql("select count(*) from notification where user_id = ?")
                .param(user)
                .query(Long.class)
                .single();
    }

    private String planStatus(UUID plan) {
        return sql.sql("select plan_status from trading_plan where plan_id = ?")
                .param(plan)
                .query(String.class)
                .single();
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, request, null);
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t085.invalid", role.name(), now, now).update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId) {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
