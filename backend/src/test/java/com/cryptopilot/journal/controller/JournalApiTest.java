package com.cryptopilot.journal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import java.sql.Timestamp;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * SCR-20 over HTTP and the real schema: the caller's trades only, the latest entry first, filtered by status, pair and
 * market, paged; a closed trade carries the net P/L of BR-38, provisional while its funding is incomplete.
 *
 * <p>Rule: UC-22; SRS 3.6.1; BR-38; D-97 and its note of 2026-10-24; CR-04.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class JournalApiTest {

    private static final String JOURNAL = "/api/v1/journal";
    private static final Instant T0 = Instant.parse("2026-10-20T08:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private UUID btc;
    private UUID eth;

    @BeforeEach
    void insertPairs() {
        btc = pair("JA");
        eth = pair("JB");
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from trading_plan where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        for (UUID pair : pairs) {
            new MarketTestData(sql, Instant.now()).remove(pair);
        }
    }

    @Test
    void list_showsTheCallersTradesLatestFirst_withTheNetPnlOfAClosedOne() throws Exception {
        UUID trader = account(UserRole.TRADER);
        UUID other = account(UserRole.TRADER);
        UUID plan = plan(trader, btc);
        UUID closed = closedSpot(trader, btc, plan, T0);
        UUID open = openFutures(trader, eth, T0.plusSeconds(60));
        openFutures(other, eth, T0.plusSeconds(120));

        as(trader, JOURNAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.items[0].id").value(open.toString()))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.items[0].planId").doesNotExist())
                .andExpect(jsonPath("$.items[0].leverage").value(5))
                .andExpect(jsonPath("$.items[0].netPnl").doesNotExist())
                .andExpect(jsonPath("$.items[0].provisional").value(false))
                .andExpect(jsonPath("$.items[1].id").value(closed.toString()))
                .andExpect(jsonPath("$.items[1].planId").value(plan.toString()))
                .andExpect(jsonPath("$.items[1].pairId").value(btc.toString()))
                .andExpect(jsonPath("$.items[1].market").value("SPOT"))
                .andExpect(jsonPath("$.items[1].direction").value("LONG"))
                .andExpect(jsonPath("$.items[1].source").value("SIMULATED"))
                .andExpect(jsonPath("$.items[1].status").value("CLOSED"))
                .andExpect(jsonPath("$.items[1].closeReason").value("TAKE_PROFIT"))
                .andExpect(jsonPath("$.items[1].entryPrice").value("100.000000000000"))
                .andExpect(jsonPath("$.items[1].exitPrice").value("110.000000000000"))
                .andExpect(jsonPath("$.items[1].realizedPnl").value("10.00000000"))
                .andExpect(jsonPath("$.items[1].feeAmount").value("0.21000000"))
                .andExpect(jsonPath("$.items[1].fundingFeeAmount").doesNotExist())
                .andExpect(jsonPath("$.items[1].netPnl").value("9.79000000"))
                .andExpect(jsonPath("$.items[1].provisional").value(false));
    }

    @Test
    void list_marksTheNetPnlProvisional_whileTheFundingIsIncomplete() throws Exception {
        UUID trader = account(UserRole.TRADER);
        closedFuturesMissingFunding(trader, eth, T0);

        as(trader, JOURNAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fundingFeeAmount").value("-0.50000000"))
                .andExpect(jsonPath("$.items[0].netPnl").value("-21.00000000"))
                .andExpect(jsonPath("$.items[0].provisional").value(true));
    }

    @Test
    void list_leavesTheNetPnlOut_ofAClosedManualTradeWithoutItsAmounts() throws Exception {
        UUID trader = account(UserRole.TRADER);
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, market_type, direction, entry_price,
                                                     entry_time, quantity, source, trade_status, created_at,
                                                     updated_at, version)
                        values (gen_random_uuid(), ?, ?, 'SPOT', 'LONG', 100, ?, 1, 'MANUAL', 'CLOSED', ?, ?, 0)""").params(trader, btc, ts(T0), ts(T0), ts(T0)).update();

        as(trader, JOURNAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.items[0].netPnl").doesNotExist())
                .andExpect(jsonPath("$.items[0].provisional").value(false));
    }

    @Test
    void list_filtersByStatusPairAndMarket_andPages() throws Exception {
        UUID trader = account(UserRole.TRADER);
        closedSpot(trader, btc, plan(trader, btc), T0);
        openFutures(trader, eth, T0.plusSeconds(60));
        openFutures(trader, eth, T0.plusSeconds(120));

        as(trader, JOURNAL + "?status=CLOSED").andExpect(jsonPath("$.total").value(1));
        as(trader, JOURNAL + "?status=OPEN").andExpect(jsonPath("$.total").value(2));
        as(trader, JOURNAL + "?pairId=" + btc).andExpect(jsonPath("$.total").value(1));
        as(trader, JOURNAL + "?market=FUTURES&pairId=" + eth)
                .andExpect(jsonPath("$.total").value(2));
        as(trader, JOURNAL + "?market=SPOT&status=OPEN")
                .andExpect(jsonPath("$.total").value(0));
        as(trader, JOURNAL + "?page=2&pageSize=2")
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].market").value("SPOT"));
    }

    @Test
    void list_refusesAPageOutOfRange() throws Exception {
        UUID trader = account(UserRole.TRADER);

        as(trader, JOURNAL + "?pageSize=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").value("MSG15"));
        as(trader, JOURNAL + "?page=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page").value("MSG15"));
    }

    @Test
    void list_isForTradersOnly() throws Exception {
        mvc.perform(get(JOURNAL)).andExpect(status().isUnauthorized());
        as(account(UserRole.ADMIN), JOURNAL).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------

    private UUID closedSpot(UUID trader, UUID pair, UUID plan, Instant entry) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, plan_id, market_type, direction,
                                                     entry_price, entry_time, quantity, entry_liquidity,
                                                     entry_fee_amount, source, trade_status, exit_price, exit_time,
                                                     close_reason, realized_pnl, fee_amount, created_at, updated_at,
                                                     version)
                        values (?, ?, ?, ?, 'SPOT', 'LONG', 100, ?, 1, 'TAKER', 0.1, 'SIMULATED', 'CLOSED', 110, ?,
                                'TAKE_PROFIT', 10, 0.21, ?, ?, 1)""")
                .params(id, trader, pair, plan, ts(entry), ts(entry.plusSeconds(3600)), ts(entry), ts(entry))
                .update();
        return id;
    }

    private UUID openFutures(UUID trader, UUID pair, Instant entry) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, market_type, direction, leverage,
                                                     entry_price, entry_time, quantity, source, trade_status,
                                                     created_at, updated_at, version)
                        values (?, ?, ?, 'FUTURES', 'SHORT', 5, 2000, ?, 0.5, 'MANUAL', 'OPEN', ?, ?, 0)""").params(id, trader, pair, ts(entry), ts(entry), ts(entry)).update();
        return id;
    }

    private void closedFuturesMissingFunding(UUID trader, UUID pair, Instant entry) {
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, market_type, direction, leverage,
                                                     entry_price, entry_time, quantity, source, trade_status,
                                                     exit_price, exit_time, close_reason, realized_pnl, fee_amount,
                                                     funding_fee_amount, funding_incomplete, missing_funding_times,
                                                     created_at, updated_at, version)
                        values (gen_random_uuid(), ?, ?, 'FUTURES', 'LONG', 5, 2000, ?, 0.5, 'MANUAL', 'CLOSED', 1960,
                                ?, 'STOP_LOSS', -20, 0.5, -0.5, true, array[?::timestamptz], ?, ?, 1)""")
                .params(
                        trader,
                        pair,
                        ts(entry),
                        ts(entry.plusSeconds(3600)),
                        ts(entry.plusSeconds(1800)),
                        ts(entry),
                        ts(entry))
                .update();
    }

    private UUID plan(UUID trader, UUID pair) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, analysis_timeframe, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 100, 90, 110, 1000, 1, 'DRAFT', '1h', ?, ?)""").params(id, trader, pair, ts(T0), ts(T0)).update();
        return id;
    }

    private UUID pair(String prefix) {
        String symbol = prefix + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        UUID id = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        pairs.add(id);
        return id;
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""")
                .params(id, id + "@journal.invalid", role.name(), ts(T0), ts(T0))
                .update();
        accounts.add(id);
        return id;
    }

    private ResultActions as(UUID user, String path) throws Exception {
        UserRole role = sql.sql("select role from user_account where user_id = ?")
                .param(user)
                .query(String.class)
                .optional()
                .map(UserRole::valueOf)
                .orElseThrow();
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(user, role)));
    }

    private String bearer(UUID userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
