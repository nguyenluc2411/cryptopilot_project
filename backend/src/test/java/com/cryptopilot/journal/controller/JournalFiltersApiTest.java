package com.cryptopilot.journal.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.journal.TradeRow;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.FixedClockConfig;
import com.cryptopilot.support.PlanTraders;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * The filters of SCR-20, one trade of SCR-21 and the strategy list, over HTTP and the real schema. The clock stands at
 * {@link FixedClockConfig#NOW}, 2026-09-29 17:30 UTC+7.
 *
 * <p>Rule: UC-22; SRS 3.6.1; BR-38, BR-41, BR-42; CR-04, CR-08; D-97, D-139, D-140.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class JournalFiltersApiTest {

    private static final String JOURNAL = "/api/v1/journal";
    private static final String STRATEGIES = "/api/v1/strategies";
    private static final String ALL = JOURNAL + "?source=ALL";
    private static final Instant EXIT = Instant.parse("2026-09-20T08:00:00Z");
    private static final Instant MIDNIGHT = Instant.parse("2026-09-19T17:00:00Z");
    private static final UUID BREAKOUT = UUID.fromString("019b76da-a800-7001-8000-000000000001");
    private static final UUID SWING = UUID.fromString("019b76da-a800-7001-8000-000000000002");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private PlanTraders traders;
    private UUID pair;
    private UUID otherPair;
    private UUID trader;

    @BeforeEach
    void setUp() {
        traders = new PlanTraders(sql, jwtEncoder, FixedClockConfig.NOW);
        MarketTestData market = new MarketTestData(sql, FixedClockConfig.NOW);
        long suffix = System.nanoTime() % 1_000_000_000L;
        pair = market.pair("JF" + suffix + "USDT", true, true, "TRADING", "TRADING", 0);
        otherPair = market.pair("JG" + suffix + "USDT", true, true, "TRADING", "TRADING", 0);
        trader = account(UserRole.TRADER);
    }

    @AfterEach
    void clear() {
        for (UUID account : accounts) {
            sql.sql("delete from trading_journal where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from trading_plan where user_id = ?").param(account).update();
        }
        traders.clear();
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        new MarketTestData(sql, FixedClockConfig.NOW).remove(pair);
        new MarketTestData(sql, FixedClockConfig.NOW).remove(otherPair);
    }

    /** Unlike SCR-23, the list hides no trade unless told to (D-140). */
    @ParameterizedTest
    @ValueSource(strings = {JOURNAL, ALL})
    void D140_source_defaultsToBoth(String path) throws Exception {
        UUID simulated = closed().simulated(plan()).insert(sql);
        UUID manual = closed().insert(sql);

        as(trader, path)
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(simulated.toString(), manual.toString())));
    }

    @Test
    void BR41_source_isFilteredWhenNamed() throws Exception {
        UUID simulated = closed().simulated(plan()).insert(sql);
        UUID manual = closed().insert(sql);

        ids(JOURNAL + "?source=SIMULATED", simulated);
        ids(JOURNAL + "?source=MANUAL", manual);
    }

    @ParameterizedTest
    @ValueSource(strings = {JOURNAL, JOURNAL + "?market=ALL"})
    void D140_market_defaultsToBoth_andAcceptsAll(String path) throws Exception {
        UUID spot = closed().insert(sql);
        UUID futures = closed().futures("LONG").insert(sql);

        as(trader, path)
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(spot.toString(), futures.toString())));
    }

    @Test
    void UC22_market_isFilteredWhenNamed() throws Exception {
        UUID spot = closed().insert(sql);
        UUID futures = closed().futures("LONG").insert(sql);

        ids(JOURNAL + "?market=SPOT", spot);
        ids(JOURNAL + "?market=FUTURES", futures);
    }

    @Test
    void UC22_strategy_isFilteredAndNamed_andNullWhenUnassigned() throws Exception {
        UUID assigned = closed().strategy(BREAKOUT).insert(sql);
        closed().strategy(SWING).insert(sql);
        UUID unassigned = closed().insert(sql);

        as(trader, ALL + "&strategyId=" + BREAKOUT)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(assigned.toString()))
                .andExpect(jsonPath("$.items[0].strategyId").value(BREAKOUT.toString()))
                .andExpect(jsonPath("$.items[0].strategyName").value("Breakout"));
        as(trader, JOURNAL + "/" + unassigned)
                .andExpect(jsonPath("$.strategyId").value(nullValue()))
                .andExpect(jsonPath("$.strategyName").value(nullValue()));
    }

    /** The funding alone decides each of these: without it the first would be a win, the others a loss and a win. */
    @ParameterizedTest
    @CsvSource({"BREAKEVEN, 5, 1, -4", "WIN, -1, 1, 3", "LOSS, 5, 1, -5"})
    void BR42_result_readsTheNetPnlWithItsFunding(String result, String realized, String fee, String funding)
            throws Exception {
        UUID match =
                closed().futures("LONG").amounts(realized, fee).funding(funding).insert(sql);

        ids(ALL + "&result=" + result, match);
    }

    @Test
    void BR42_eachResult_matchesOnlyItsTrades() throws Exception {
        UUID win = closed().amounts("3", "1").insert(sql);
        UUID loss = closed().amounts("1", "3").insert(sql);
        UUID breakeven = closed().amounts("2", "2").insert(sql);

        ids(ALL + "&result=WIN", win);
        ids(ALL + "&result=LOSS", loss);
        ids(ALL + "&result=BREAKEVEN", breakeven);
    }

    @Test
    void BR42_result_neverMatchesAnOpenTrade() throws Exception {
        TradeRow.open(trader, pair, EXIT).insert(sql);

        as(trader, ALL).andExpect(jsonPath("$.total").value(1));
        ids(ALL + "&result=BREAKEVEN");
        ids(ALL + "&result=WIN&status=OPEN");
    }

    /** As in the statistics: a missing fee counts as 0, and a closed trade without a realized P/L has no result. */
    @Test
    void D139_result_coalescesTheFee_andSkipsATradeWithoutAmounts() throws Exception {
        UUID withoutFee = closed().amounts("3", "0").noFee().insert(sql);
        closed().noAmounts().insert(sql);

        ids(ALL + "&result=WIN", withoutFee);
        ids(ALL + "&result=BREAKEVEN");
    }

    /** The row, its detail and the statistics agree on a closed trade without a fee: realized + funding (D-139). */
    @Test
    void D139_netPnl_coalescesAMissingFee_likeTheStatistics() throws Exception {
        UUID id = closed().futures("LONG")
                .amounts("3", "0")
                .noFee()
                .funding("-0.5")
                .insert(sql);
        String net = "2.50000000";

        as(trader, ALL)
                .andExpect(jsonPath("$.items[0].feeAmount").value(nullValue()))
                .andExpect(jsonPath("$.items[0].netPnl").value(net));
        as(trader, JOURNAL + "/" + id).andExpect(jsonPath("$.netPnl").value(net));
        as(trader, JOURNAL + "/performance/summary?source=ALL")
                .andExpect(jsonPath("$.totalNetPnl").value(net));
    }

    /** Only the fee is coalesced: without a realized P/L neither the row nor the statistics have a net P/L. */
    @Test
    void D139_netPnl_staysNullWithoutARealizedPnl() throws Exception {
        UUID id = closed().noAmounts().insert(sql);

        as(trader, JOURNAL + "/" + id).andExpect(jsonPath("$.netPnl").value(nullValue()));
        as(trader, JOURNAL + "/performance/summary?source=ALL")
                .andExpect(jsonPath("$.trades").value(0));
    }

    /** The day of UTC+7 starts at 17:00 UTC, and the period is that of the entry, which an OPEN trade has too. */
    @Test
    void CR08_periodBoundary_isMidnightOfUtcPlus7() throws Exception {
        UUID before = TradeRow.open(trader, pair, MIDNIGHT.minusSeconds(1)).insert(sql);
        UUID at = TradeRow.open(trader, pair, MIDNIGHT).insert(sql);

        ids(ALL + "&from=2026-09-20", at);
        ids(ALL + "&to=2026-09-20", before);
        ids(ALL + "&from=2026-09-19&to=2026-09-21", at, before);
    }

    @Test
    void UC22_period_readsTheEntryAndNotTheExit() throws Exception {
        UUID entered = TradeRow.closed(trader, pair, MIDNIGHT.plusSeconds(1800)).insert(sql);

        ids(ALL + "&to=2026-09-20", entered);
        ids(ALL + "&from=2026-09-20");
    }

    @Test
    void CR08_defaultPeriod_includesToday() throws Exception {
        UUID today = TradeRow.open(trader, pair, FixedClockConfig.NOW).insert(sql);

        ids(ALL, today);
    }

    @Test
    void UC22_filters_combine() throws Exception {
        UUID match =
                closed().amounts("3", "1").strategy(BREAKOUT).futures("LONG").insert(sql);
        closed().amounts("1", "3").strategy(BREAKOUT).futures("LONG").insert(sql);
        closed().amounts("3", "1").strategy(SWING).futures("LONG").insert(sql);
        closed().amounts("3", "1").strategy(BREAKOUT).insert(sql);
        TradeRow.closed(trader, otherPair, EXIT)
                .amounts("3", "1")
                .strategy(BREAKOUT)
                .futures("LONG")
                .insert(sql);
        TradeRow.closed(trader, pair, EXIT.plusSeconds(3 * 24 * 3600))
                .amounts("3", "1")
                .strategy(BREAKOUT)
                .futures("LONG")
                .insert(sql);

        ids(
                ALL + "&from=2026-09-20&to=2026-09-21&status=CLOSED&market=FUTURES&result=WIN&pairId=" + pair
                        + "&strategyId=" + BREAKOUT,
                match);
    }

    /** Rows with the same entry time keep one order across pages: nothing is repeated, nothing is lost. */
    @Test
    void CR04_pagingWithFilters_countsTheFilteredRowsInAStableOrder() throws Exception {
        for (int i = 0; i < 3; i++) {
            closed().amounts("3", "1").insert(sql);
        }
        closed().amounts("1", "3").insert(sql);
        String byId = """
                        select journal_id::text from trading_journal
                         where user_id = ? and realized_pnl = 3 order by journal_id desc""";
        List<String> everyId = sql.sql(byId).param(trader).query(String.class).list();

        as(trader, ALL + "&result=WIN&page=1&pageSize=2")
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].id", contains(everyId.get(0), everyId.get(1))));
        as(trader, ALL + "&result=WIN&page=2&pageSize=2")
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[*].id", contains(everyId.get(2))));
    }

    @ParameterizedTest
    @CsvSource({
        "?from=soon, from, is invalid",
        "?to=2026-13-01, to, is invalid",
        "?market=BOTH, market, is invalid",
        "?source=DEMO, source, is invalid",
        "?strategyId=1, strategyId, is invalid",
        "?result=DRAW, result, is invalid",
        "?from=2026-09-20&to=2026-09-20, from, MSG15",
        "?from=2026-09-21&to=2026-09-20, from, MSG15",
        "?from=2026-09-30, from, MSG15",
        "?from=0000-12-31, from, MSG15",
        "?to=0000-12-31, to, MSG15",
    })
    void UC22_badFilter_namesTheField(String query, String field, String message) throws Exception {
        as(trader, JOURNAL + query)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors." + field).value(message));
    }

    @Test
    void UC22_ownTrade_answersTheListRowAndTheReview() throws Exception {
        UUID id = closed().amounts("3", "1")
                .strategy(BREAKOUT)
                .review("Range break", "Wait for the retest", "Sized too big", "https://cdn.invalid/chart.png")
                .insert(sql);

        as(trader, JOURNAL + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.pairId").value(pair.toString()))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.netPnl").value("2.00000000"))
                .andExpect(jsonPath("$.strategyId").value(BREAKOUT.toString()))
                .andExpect(jsonPath("$.strategyName").value("Breakout"))
                .andExpect(jsonPath("$.setupDescription").value("Range break"))
                .andExpect(jsonPath("$.lessonsLearned").value("Wait for the retest"))
                .andExpect(jsonPath("$.mistakes").value("Sized too big"))
                .andExpect(jsonPath("$.chartImageUrl").value("https://cdn.invalid/chart.png"))
                .andExpect(jsonPath("$.entry").doesNotExist());
    }

    @Test
    void UC22_anOpenTradeWithoutAReview_answersNulls() throws Exception {
        UUID id = TradeRow.open(trader, pair, EXIT).insert(sql);

        as(trader, JOURNAL + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.netPnl").value(nullValue()))
                .andExpect(jsonPath("$.setupDescription").value(nullValue()))
                .andExpect(jsonPath("$.chartImageUrl").value(nullValue()));
    }

    @Test
    void UC22_anotherTradersTrade_isNotFoundLikeAnUnknownId() throws Exception {
        UUID theirs = TradeRow.closed(account(UserRole.TRADER), pair, EXIT).insert(sql);

        as(trader, JOURNAL + "/" + theirs)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(trader, JOURNAL + "/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
    }

    @Test
    void UC22_oneTrade_refusesAnIdThatIsNotAUuid() throws Exception {
        as(trader, JOURNAL + "/not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    @Test
    void UC22_strategies_areListedOnTheFreePlan() throws Exception {
        expectTheCatalogue(trader);
    }

    @Test
    void UC22_strategies_areListedOnAPaidPlan() throws Exception {
        UUID subscriber = account(UserRole.TRADER);
        traders.subscribe(subscriber, PlanTier.PRO);

        expectTheCatalogue(subscriber);
    }

    @Test
    void UC22_strategies_comeInTheOrderOfTheirNames() throws Exception {
        List<String> names = sql.sql("select strategy_name from trading_strategy order by strategy_name, strategy_id")
                .query(String.class)
                .list();

        as(trader, STRATEGIES)
                .andExpect(jsonPath("$[*].name", contains(names.toArray(String[]::new))))
                .andExpect(jsonPath("$[0].code").doesNotExist());
    }

    @Test
    void UC22_guest_isChallenged_andAnAdminRefused() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        for (String path : List.of(STRATEGIES, JOURNAL + "/" + UUID.randomUUID())) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            as(admin, path).andExpect(status().isForbidden());
        }
    }

    private void expectTheCatalogue(UUID user) throws Exception {
        as(user, STRATEGIES)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + BREAKOUT + "')].name", contains("Breakout")))
                .andExpect(jsonPath("$[?(@.id == '" + SWING + "')].name", contains("Swing")));
    }

    private TradeRow closed() {
        return TradeRow.closed(trader, pair, EXIT);
    }

    /** The ids of a list, in its order. */
    private void ids(String path, UUID... expected) throws Exception {
        as(trader, path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(expected.length))
                .andExpect(jsonPath("$.items.length()").value(expected.length));
        for (int i = 0; i < expected.length; i++) {
            as(trader, path).andExpect(jsonPath("$.items[" + i + "].id").value(expected[i].toString()));
        }
    }

    private UUID plan() {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(EXIT);
        String insert = """
                        insert into trading_plan (plan_id, user_id, pair_id, market_type, direction, entry_type,
                                                  entry_price, stop_loss_price, take_profit_price, capital_amount,
                                                  risk_percent, plan_status, analysis_timeframe, created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'LONG', 'LIMIT', 100, 90, 110, 1000, 1, 'DRAFT', '1h', ?, ?)""";
        sql.sql(insert).params(id, trader, pair, at, at).update();
        return id;
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(FixedClockConfig.NOW);
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""")
                .params(id, id + "@journal-filters.invalid", role.name(), at, at)
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

    private String bearer(UUID user, UserRole role) {
        Instant now = FixedClockConfig.NOW;
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(user.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(JwtConfig.ROLE_CLAIM, role.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
