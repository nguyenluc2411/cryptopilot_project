package com.cryptopilot.journal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.journal.client.ChartImageStorage;
import com.cryptopilot.journal.client.ChartImageStorageException;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
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
 * SCR-22 over HTTP and the real schema: a Trader records, edits and deletes their own manual trades; nobody else can,
 * and a simulated trade is never edited or deleted this way. Manual trades carry source MANUAL and are reported apart
 * from simulated ones in the list and the statistics.
 *
 * <p>Rule: BR-22, BR-35, BR-38, BR-40, BR-41; UC-24; SRS 3.6.2; D-97, D-139, D-140.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ManualTradeApiTest {

    private static final String JOURNAL = "/api/v1/journal";
    private static final Instant ENTRY = Instant.parse("2026-11-03T08:00:00Z");
    /** The BREAKOUT strategy V3 seeded. */
    private static final UUID BREAKOUT = UUID.fromString("019b76da-a800-7001-8000-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private ChartImageStorage images;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID pair;
    private UUID trader;

    @BeforeEach
    void insertRows() {
        String symbol = "JM" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        trader = account();
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
        new MarketTestData(sql, Instant.now()).remove(pair);
    }

    /**
     * BR-35, BR-38: a closed Spot LONG of 2 from 100 to 110 with 0.4 fees: realized 20, net 19.6, closed by MANUAL;
     * the strategy and review texts are kept.
     */
    @Test
    void BR41_aClosedTrade_isRecordedAsManual_withItsPnlFromThePrices() throws Exception {
        as(trader, post(JOURNAL), closedSpot("110", "\"feeAmount\": 0.4, \"strategyId\": \"%s\"".formatted(BREAKOUT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.planId").doesNotExist())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closeReason").value("MANUAL"))
                .andExpect(jsonPath("$.entryPrice").value("100.000000000000"))
                .andExpect(jsonPath("$.exitPrice").value("110.000000000000"))
                .andExpect(jsonPath("$.realizedPnl").value("20.00000000"))
                .andExpect(jsonPath("$.feeAmount").value("0.40000000"))
                .andExpect(jsonPath("$.netPnl").value("19.60000000"))
                .andExpect(jsonPath("$.strategyName").value("Breakout"))
                .andExpect(jsonPath("$.setupDescription").value("From another exchange"))
                .andExpect(jsonPath("$.learning").doesNotExist());
    }

    /** D-139: without fees the fee is stored as 0, so the statistics count the trade; without an exit it is OPEN. */
    @Test
    void BR41_anOpenFuturesTrade_isRecordedOpen_withAFeeOf0() throws Exception {
        String id = id(as(trader, post(JOURNAL), """
                        {"pairId": "%s", "market": "FUTURES", "direction": "SHORT", "leverage": 10,
                         "entryPrice": 2000, "quantity": 0.5, "entryTime": "%s",
                         "stopLoss": 2100, "takeProfit": 1800}
                        """.formatted(pair, ENTRY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.leverage").value(10))
                .andExpect(jsonPath("$.realizedPnl").doesNotExist())
                .andExpect(jsonPath("$.netPnl").doesNotExist()));

        assertThat(sql.sql("select fee_amount from trading_journal where journal_id = ?::uuid")
                        .param(id)
                        .query(java.math.BigDecimal.class)
                        .single())
                .isEqualByComparingTo("0");
    }

    /** SRS 3.6.2: each broken rule is reported under its field; nothing is stored. */
    @Test
    void BR41_aTradeBreakingTheRules_isRefusedFieldByField() throws Exception {
        as(trader, post(JOURNAL), """
                        {"pairId": "%s", "market": "SPOT", "direction": "SHORT", "entryPrice": -1, "quantity": 1,
                         "entryTime": "%s", "exitPrice": 105, "strategyId": "%s"}
                        """.formatted(pair, ENTRY, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.entryPrice").value("MSG15"));

        as(trader, post(JOURNAL), """
                        {"pairId": "%s", "market": "SPOT", "direction": "SHORT", "entryPrice": 100, "quantity": 1,
                         "entryTime": "%s", "exitPrice": 105, "stopLoss": 90, "takeProfit": 110,
                         "strategyId": "%s"}
                        """.formatted(pair, ENTRY, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.direction").value("MSG16"))
                .andExpect(jsonPath("$.errors.exitTime").value("MSG01"))
                .andExpect(jsonPath("$.errors.stopLoss").value("MSG16"))
                .andExpect(jsonPath("$.errors.takeProfit").value("MSG16"))
                .andExpect(jsonPath("$.errors.strategyId").value("MSG15"));

        assertThat(rows(trader)).isZero();
    }

    /** BR-41: the owner edits every field; the P/L follows the new prices. */
    @Test
    void BR41_theOwner_editsTheirManualTrade() throws Exception {
        String id = id(as(trader, post(JOURNAL), closedSpot("110", "\"feeAmount\": 0.4")));

        as(trader, put(JOURNAL + "/" + id), closedSpot("90", "\"mistakes\": \"Held too long\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitPrice").value("90.000000000000"))
                .andExpect(jsonPath("$.realizedPnl").value("-20.00000000"))
                .andExpect(jsonPath("$.netPnl").value("-20.00000000"))
                .andExpect(jsonPath("$.mistakes").value("Held too long"));
    }

    /** MSG41: another Trader can neither edit nor delete the trade, nor learn that it exists. */
    @Test
    void BR41_anotherTrader_cannotEditOrDeleteTheTrade() throws Exception {
        String id = id(as(trader, post(JOURNAL), closedSpot("110", "")));
        UUID other = account();

        as(other, put(JOURNAL + "/" + id), closedSpot("90", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(other, delete(JOURNAL + "/" + id), null).andExpect(status().isNotFound());

        as(trader, get(JOURNAL + "/" + id), null)
                .andExpect(jsonPath("$.exitPrice").value("110.000000000000"));
    }

    /** BR-40: a simulated trade's execution facts are fixed; the manual endpoints refuse it with MSG43. */
    @Test
    void BR40_aSimulatedTrade_isNeitherEditedNorDeleted() throws Exception {
        UUID simulated = simulated(trader);

        as(trader, put(JOURNAL + "/" + simulated), closedSpot("90", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG43"));
        as(trader, delete(JOURNAL + "/" + simulated), null).andExpect(status().isConflict());

        assertThat(rows(trader)).isEqualTo(1);
        assertThat(sql.sql("select exit_price from trading_journal where journal_id = ?")
                        .param(simulated)
                        .query(java.math.BigDecimal.class)
                        .single())
                .isEqualByComparingTo("110");
    }

    /** BR-41: the owner deletes the trade permanently, and its chart image after the commit. */
    @Test
    void BR41_theOwner_deletesTheTrade_withItsChartImage() throws Exception {
        String plain = id(as(trader, post(JOURNAL), closedSpot("110", "")));
        as(trader, delete(JOURNAL + "/" + plain), null).andExpect(status().isNoContent());
        verify(images, never()).destroy(any());

        String pictured = id(as(trader, post(JOURNAL), closedSpot("110", "")));
        sql.sql("update trading_journal set chart_image_url = 'https://img.example/c.png',"
                        + " chart_image_public_id = 'cryptopilot/journal/x/c' where journal_id = ?::uuid")
                .param(pictured)
                .update();
        doThrow(new ChartImageStorageException("down")).when(images).destroy("cryptopilot/journal/x/c");

        as(trader, delete(JOURNAL + "/" + pictured), null).andExpect(status().isNoContent());

        verify(images).destroy("cryptopilot/journal/x/c");
        assertThat(rows(trader)).isZero();
        as(trader, get(JOURNAL + "/" + pictured), null).andExpect(status().isNotFound());
    }

    /**
     * BR-41, D-140: the list shows both sources unless one is asked for; the statistics read SIMULATED by default,
     * so a manual trade never mixes into the simulated figures, and MANUAL reads it alone.
     */
    @Test
    void BR41_manualTrades_areReportedApartFromSimulatedOnes() throws Exception {
        id(as(trader, post(JOURNAL), closedSpot("110", "")));
        simulated(trader);
        String period = "from=2026-11-01&to=2026-11-10";

        as(trader, get(JOURNAL + "?" + period), null)
                .andExpect(jsonPath("$.total").value(2));
        as(trader, get(JOURNAL + "?source=MANUAL&" + period), null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].source").value("MANUAL"));
        as(trader, get(JOURNAL + "?source=SIMULATED&" + period), null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].source").value("SIMULATED"));

        as(trader, get(JOURNAL + "/performance/summary?" + period), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trades").value(1))
                .andExpect(jsonPath("$.totalNetPnl").value("9.79000000"));
        as(trader, get(JOURNAL + "/performance/summary?source=MANUAL&" + period), null)
                .andExpect(jsonPath("$.trades").value(1))
                .andExpect(jsonPath("$.totalNetPnl").value("20.00000000"));
        as(trader, get(JOURNAL + "/performance/summary?source=ALL&" + period), null)
                .andExpect(jsonPath("$.trades").value(2));
    }

    /** A closed Spot LONG of 2 from 100 to {@code exit}, entered an hour before its exit, without fees. */
    private String closedSpot(String exit, String extra) {
        return """
                {"pairId": "%s", "market": "SPOT", "direction": "LONG", "entryPrice": 100, "quantity": 2,
                 "entryTime": "%s", "exitPrice": %s, "exitTime": "%s", "stopLoss": 95, "takeProfit": 120,
                 "setupDescription": "From another exchange"%s}
                """.formatted(pair, ENTRY, exit, ENTRY.plusSeconds(3600), extra.isEmpty() ? "" : ", " + extra);
    }

    /** A CLOSED simulated Spot LONG of 1 from 100 to 110 with fees 0.21: net 9.79. */
    private UUID simulated(UUID owner) {
        UUID plan = UUID.randomUUID();
        sql.sql("""
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, analysis_timeframe, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 100, 90, 110, 1000, 1, 'DRAFT', '1h', ?, ?)""").params(plan, owner, pair, ts(ENTRY), ts(ENTRY)).update();
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into trading_journal (journal_id, user_id, pair_id, plan_id, market_type, direction,
                                                     entry_price, entry_time, quantity, source, trade_status,
                                                     exit_price, exit_time, close_reason, realized_pnl, fee_amount,
                                                     created_at, updated_at, version)
                        values (?, ?, ?, ?, 'SPOT', 'LONG', 100, ?, 1, 'SIMULATED', 'CLOSED', 110, ?, 'TAKE_PROFIT',
                                10, 0.21, ?, ?, 2)""")
                .params(id, owner, pair, plan, ts(ENTRY), ts(ENTRY.plusSeconds(3600)), ts(ENTRY), ts(ENTRY))
                .update();
        return id;
    }

    private long rows(UUID owner) {
        return sql.sql("select count(*) from trading_journal where user_id = ?")
                .param(owner)
                .query(Long.class)
                .single();
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@manual.invalid", ts(ENTRY), ts(ENTRY)).update();
        accounts.add(id);
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

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
