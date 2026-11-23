package com.cryptopilot.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * The PnL analysis end to end (TR-28) against PostgreSQL: the transfers of the ledger are taken out of a wallet's day,
 * and the closed Futures positions are read with their net result. Every test writes its own Trader and removes it.
 *
 * <p>Rule: TR-28.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PaperPnlAnalysisApiTest {

    private static final String PAPER = "/api/v1/paper";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private StringRedisTemplate redis;

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private UUID pair;

    @BeforeEach
    void setUp() {
        Set<String> prices = redis.keys("px:*");
        if (prices != null && !prices.isEmpty()) {
            redis.delete(prices);
        }
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        pair = new MarketTestData(sql, Instant.now())
                .pair("PA" + (System.nanoTime() % 1_000_000_000L) + "USDT", false, true, null, "TRADING", 0);
    }

    @AfterEach
    void removeRows() {
        for (UUID trader : traders) {
            for (String table :
                    List.of("futures_position", "wallet_ledger_entry", "wallet_balance", "equity_snapshot")) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(trader)
                        .update();
            }
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        UUID base = sql.sql("select base_coin_id from crypto_pair where pair_id = ?")
                .param(pair)
                .query(UUID.class)
                .single();
        sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        sql.sql("delete from coin where coin_id = ?").param(base).update();
        for (String symbol : List.of("USDT", "BTC", "ETH", "BNB")) {
            if (!preStoredCoins.contains(symbol)) {
                sql.sql("delete from coin where symbol = ?").param(symbol).update();
            }
        }
    }

    @Test
    void TR28_aTransferToFutures_isNotAGainOfFutures_norALossOfSpot() throws Exception {
        UUID trader = opened();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // The snapshot of today as the opening left the wallets, before the transfer.
        snapshot(trader, today, "10000", "5000");
        as(trader, post(PAPER + "/transfers"), "{\"asset\": \"USDT\", \"from\": \"SPOT\", \"amount\": 100}")
                .andExpect(status().isCreated());

        as(trader, get(PAPER + "/pnl/analysis").param("market", "FUTURES").param("days", "7"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("FUTURES"))
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(result -> assertThat(new BigDecimal(
                                (String) JsonPath.read(result.getResponse().getContentAsString(), "$.todayPnl")))
                        .isEqualByComparingTo("0"))
                .andExpect(jsonPath("$.daily.length()").value(1))
                .andExpect(jsonPath("$.trades.closed").value(0));
        as(trader, get(PAPER + "/pnl/analysis").param("market", "SPOT").param("days", "7"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trades.closed").value(0));
    }

    @Test
    void TR28_theClosedFuturesPositions_areCountedByTheirNetResult() throws Exception {
        UUID trader = opened();
        position(trader, "40", "-5", "0");
        position(trader, "-30", "2", "3");
        position(trader, "5", "-5", "0");

        String body = as(
                        trader,
                        get(PAPER + "/pnl/analysis").param("market", "FUTURES").param("days", "30"),
                        null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat((Integer) JsonPath.read(body, "$.trades.closed")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body, "$.trades.wins")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.trades.losses")).isEqualTo(1);
        assertThat(new BigDecimal((String) JsonPath.read(body, "$.trades.totalPnl")))
                .isEqualByComparingTo("4");
        assertThat(new BigDecimal((String) JsonPath.read(body, "$.trades.profitFactor")))
                .isEqualByComparingTo("1.13");
    }

    @Test
    void TR28_aBadPeriodOrMarket_isRefused_andAnUnopenedAccount_isNotFound() throws Exception {
        UUID trader = opened();
        as(trader, get(PAPER + "/pnl/analysis").param("days", "14"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.days").value("MSG01"));
        as(trader, get(PAPER + "/pnl/analysis").param("market", "MARGIN"), null).andExpect(status().isBadRequest());
        as(trader(), get(PAPER + "/pnl/analysis"), null).andExpect(status().isNotFound());
        mvc.perform(get(PAPER + "/pnl/analysis")).andExpect(status().isUnauthorized());
    }

    /** A closed cross position with these realised PnL, funding and liquidation fee; no fill, so no fill fee. */
    private void position(UUID trader, String realized, String funding, String liquidationFee) {
        Timestamp at = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
        sql.sql("""
                        insert into futures_position (position_id, user_id, pair_id, position_side, direction,
                                                      margin_type, leverage, quantity, max_quantity, entry_price,
                                                      closed_quantity, realized_pnl_amount, funding_amount,
                                                      liquidation_fee_amount, position_status, close_reason,
                                                      opened_at, closed_at, created_at, updated_at)
                        values (?, ?, ?, 'BOTH', 'LONG', 'CROSSED', 10, 0, 1, 100, 1, ?, ?, ?, 'CLOSED', 'MANUAL',
                                ?, ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        trader,
                        pair,
                        new BigDecimal(realized),
                        new BigDecimal(funding),
                        new BigDecimal(liquidationFee),
                        at,
                        at,
                        at,
                        at)
                .update();
    }

    private void snapshot(UUID trader, LocalDate day, String spot, String futures) {
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into equity_snapshot (snapshot_id, user_id, snapshot_date, spot_equity_amount,
                                                     futures_equity_amount, total_equity_amount, created_at,
                                                     updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict do nothing""")
                .params(
                        UUID.randomUUID(),
                        trader,
                        Date.valueOf(day),
                        new BigDecimal(spot),
                        new BigDecimal(futures),
                        new BigDecimal(spot).add(new BigDecimal(futures)),
                        at,
                        at)
                .update();
        sql.sql("""
                        update equity_snapshot set spot_equity_amount = ?, futures_equity_amount = ?,
                               total_equity_amount = ?
                         where user_id = ? and snapshot_date = ?""")
                .params(
                        new BigDecimal(spot),
                        new BigDecimal(futures),
                        new BigDecimal(spot).add(new BigDecimal(futures)),
                        trader,
                        Date.valueOf(day))
                .update();
    }

    private UUID opened() throws Exception {
        UUID trader = trader();
        as(trader, post(PAPER + "/account"), null).andExpect(status().isCreated());
        return trader;
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@tr28.invalid", now, now).update();
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
