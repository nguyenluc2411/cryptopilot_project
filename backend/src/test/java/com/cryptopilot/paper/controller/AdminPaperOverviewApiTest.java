package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The demo exchange's overview and its dashboard figures end to end (TR-30): an account opened and a position
 * liquidated are counted, the liquidation is listed, and only an administrator reads them. Every test writes its own
 * accounts and removes them.
 *
 * <p>Rule: TR-30; UC-40.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AdminPaperOverviewApiTest {

    private static final String OVERVIEW = "/api/v1/admin/paper/overview";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void setUp() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "PO" + (System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, false, true, null, "TRADING", 0);
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            for (String table :
                    List.of("futures_position", "wallet_ledger_entry", "wallet_balance", "equity_snapshot")) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(account)
                        .update();
            }
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        UUID base = sql.sql("select base_coin_id from crypto_pair where pair_id = ?")
                .param(pair)
                .query(UUID.class)
                .single();
        sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        sql.sql("delete from coin where coin_id = ?").param(base).update();
        for (String coin : List.of("USDT", "BTC", "ETH", "BNB")) {
            if (!preStoredCoins.contains(coin)) {
                sql.sql("delete from coin where symbol = ?").param(coin).update();
            }
        }
    }

    @Test
    void TR30_theOverview_countsTheAccountsAndListsTheLatestLiquidation() throws Exception {
        UUID admin = account("ADMIN");
        String before = as(admin, get(OVERVIEW)).andReturn().getResponse().getContentAsString();
        UUID trader = account("TRADER");
        as(trader, post("/api/v1/paper/account")).andExpect(status().isCreated());
        liquidated(trader);

        String body = as(admin, get(OVERVIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.workingOrders.SPOT").isNumber())
                .andExpect(jsonPath("$.workingOrders.FUTURES").isNumber())
                .andExpect(jsonPath("$.engineRestingOrders").isNumber())
                .andExpect(jsonPath("$.latestLiquidations[0].symbol").value(symbol))
                .andExpect(jsonPath("$.latestLiquidations[0].userId").value(trader.toString()))
                .andExpect(jsonPath("$.latestLiquidations[0].marginType").value("CROSSED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(number(body, "$.accounts")).isEqualTo(number(before, "$.accounts") + 1);
        assertThat(number(body, "$.accountsOpened24h")).isEqualTo(number(before, "$.accountsOpened24h") + 1);
        assertThat(number(body, "$.liquidations24h")).isEqualTo(number(before, "$.liquidations24h") + 1);
    }

    @Test
    void TR30_theDashboard_carriesTheDemoExchangeFigures_andTheReportItsSeries() throws Exception {
        UUID admin = account("ADMIN");

        String body = as(admin, get("/api/v1/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indicators.paperAccounts").isNumber())
                .andExpect(jsonPath("$.indicators.paperAccountsOpened").isNumber())
                .andExpect(jsonPath("$.indicators.paperOpenPositions").isNumber())
                .andExpect(jsonPath("$.indicators.paperFills").isNumber())
                .andExpect(jsonPath("$.indicators.paperLiquidations").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Map<String, Object> working = JsonPath.read(body, "$.indicators.paperWorkingOrders");
        assertThat(working).containsKeys("SPOT", "FUTURES");
        as(admin, get("/api/v1/admin/reports")).andExpect(status().isOk());
    }

    @Test
    void TR30_theOverview_isForAdministratorsOnly() throws Exception {
        as(account("TRADER"), get(OVERVIEW)).andExpect(status().isForbidden());
        mvc.perform(get(OVERVIEW)).andExpect(status().isUnauthorized());
    }

    private void liquidated(UUID trader) {
        Timestamp at = Timestamp.from(Instant.now().minus(Duration.ofMinutes(5)));
        sql.sql("""
                        insert into futures_position (position_id, user_id, pair_id, position_side, direction,
                                                      margin_type, leverage, quantity, max_quantity, entry_price,
                                                      closed_quantity, realized_pnl_amount, position_status,
                                                      close_reason, opened_at, closed_at, created_at, updated_at)
                        values (?, ?, ?, 'BOTH', 'LONG', 'CROSSED', 20, 0, 1, 100, 1, -95, 'CLOSED', 'LIQUIDATION',
                                ?, ?, ?, ?)""").params(UUID.randomUUID(), trader, pair, at, at, at, at).update();
    }

    private static long number(String body, String path) {
        return ((Number) JsonPath.read(body, path)).longValue();
    }

    private UUID account(String role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@tr30.invalid", role, now, now).update();
        accounts.add(id);
        return id;
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(user)
                .query(String.class)
                .single();
        Instant issued = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(user.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        String bearer = "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
