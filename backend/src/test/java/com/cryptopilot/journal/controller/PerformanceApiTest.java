package com.cryptopilot.journal.controller;

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
 * SCR-23 over HTTP and the real schema: the plan gate of {@code ADVANCED_PERFORMANCE}, the role, the owner, the period
 * and the shape of every answer. The clock stands at {@link FixedClockConfig#NOW}, 2026-09-29 17:30 UTC+7.
 *
 * <p>Rule: UC-25; SRS 3.6.3; BR-41, BR-42, BR-62; CR-08; D-58.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class PerformanceApiTest {

    private static final String BASE = "/api/v1/journal/performance";
    private static final String SUMMARY = BASE + "/summary";
    private static final Instant EXIT = Instant.parse("2026-09-20T08:00:00Z");
    private static final Instant MIDNIGHT = Instant.parse("2026-09-19T17:00:00Z");
    private static final UUID BREAKOUT = UUID.fromString("019b76da-a800-7001-8000-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private PlanTraders traders;
    private String symbol;
    private UUID pair;

    @BeforeEach
    void setUp() {
        traders = new PlanTraders(sql, jwtEncoder, FixedClockConfig.NOW);
        symbol = "PF" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, FixedClockConfig.NOW).pair(symbol, true, true, "TRADING", "TRADING", 0);
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
    }

    @Test
    void BR62_freePlan_getsTheCountsAndNoAdvancedMetrics() throws Exception {
        UUID free = account(UserRole.TRADER);
        trade(free).amounts("10", "1").insert(sql);
        trade(free).futures("LONG").amounts("-5", "0.5").funding("-0.25").insert(sql);

        as(free, SUMMARY + "?source=ALL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trades").value(2))
                .andExpect(jsonPath("$.wins").value(1))
                .andExpect(jsonPath("$.losses").value(1))
                .andExpect(jsonPath("$.breakeven").value(0))
                .andExpect(jsonPath("$.winRate").value("0.50000000"))
                .andExpect(jsonPath("$.totalNetPnl").value("3.25000000"))
                .andExpect(jsonPath("$.provisionalTrades").value(0))
                .andExpect(jsonPath("$.totalFees").value("1.50000000"))
                .andExpect(jsonPath("$.totalFunding").value("-0.25000000"))
                .andExpect(jsonPath("$.advanced").hasJsonPath())
                .andExpect(jsonPath("$.advanced").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRO", "PREMIUM"})
    void BR62_paidPlan_getsTheAdvancedMetrics(String tier) throws Exception {
        UUID paid = account(UserRole.TRADER);
        traders.subscribe(paid, PlanTier.valueOf(tier));
        trade(paid).amounts("10", "0").stop("95").take("115").insert(sql);
        trade(paid).amounts("-5", "0").stop("95").take("115").insert(sql);

        as(paid, SUMMARY + "?source=ALL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trades").value(2))
                .andExpect(jsonPath("$.advanced.averageWin").value("10.00000000"))
                .andExpect(jsonPath("$.advanced.averageLoss").value("-5.00000000"))
                .andExpect(jsonPath("$.advanced.profitFactor").value("2.00000000"))
                .andExpect(jsonPath("$.advanced.averagePlannedRiskReward").value("3.00000000"))
                .andExpect(jsonPath("$.advanced.averageRealizedR").value("0.50000000"))
                .andExpect(jsonPath("$.advanced.tradesWithR").value(2))
                .andExpect(jsonPath("$.advanced.maxConsecutiveLosses").value(1))
                .andExpect(jsonPath("$.advanced.maxDrawdown").value("5.00000000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"by-coin", "by-strategy", "by-market", "equity-curve"})
    void BR62_freePlan_isRefusedTheChartsAndBreakdowns(String path) throws Exception {
        as(account(UserRole.TRADER), BASE + "/" + path)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"by-coin", "by-strategy", "by-market", "equity-curve"})
    void BR62_paidPlans_getTheChartsAndBreakdowns(String path) throws Exception {
        as(subscribed(PlanTier.PRO), BASE + "/" + path).andExpect(status().isOk());
        as(subscribed(PlanTier.PREMIUM), BASE + "/" + path).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary", "by-coin", "by-strategy", "by-market", "equity-curve"})
    void UC25_guest_isChallenged(String path) throws Exception {
        mvc.perform(get(BASE + "/" + path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary", "by-coin", "by-strategy", "by-market", "equity-curve"})
    void UC25_admin_isRefused(String path) throws Exception {
        as(account(UserRole.ADMIN), BASE + "/" + path).andExpect(status().isForbidden());
    }

    @Test
    void UC25_anotherTradersTrades_neverAppear() throws Exception {
        UUID paid = subscribed(PlanTier.PRO);
        UUID other = account(UserRole.TRADER);
        trade(paid).amounts("1", "0").insert(sql);
        trade(other).amounts("100", "0").insert(sql);

        as(paid, SUMMARY + "?source=ALL").andExpect(jsonPath("$.totalNetPnl").value("1.00000000"));
        as(paid, BASE + "/by-coin?source=ALL")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].netPnl").value("1.00000000"));
        as(paid, BASE + "/equity-curve?source=ALL")
                .andExpect(jsonPath("$[0].cumulativeNetPnl").value("1.00000000"));
    }

    @Test
    void BR41_source_defaultsToSimulated() throws Exception {
        UUID trader = account(UserRole.TRADER);
        trade(trader).amounts("1", "0").insert(sql);

        as(trader, SUMMARY).andExpect(jsonPath("$.trades").value(0));
        as(trader, SUMMARY + "?source=MANUAL").andExpect(jsonPath("$.trades").value(1));
    }

    @Test
    void UC25_noTrades_answersAnEmptySummaryNotAnError() throws Exception {
        as(subscribed(PlanTier.PRO), SUMMARY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trades").value(0))
                .andExpect(jsonPath("$.winRate").doesNotExist())
                .andExpect(jsonPath("$.totalNetPnl").value("0.00000000"))
                .andExpect(jsonPath("$.advanced.profitFactor").doesNotExist());
    }

    @Test
    void UC25_period_defaultsToAllTimeUpToTodayInUtcPlus7() throws Exception {
        UUID trader = account(UserRole.TRADER);
        trade(trader).amounts("1", "0").insert(sql);
        TradeRow.closed(trader, pair, Instant.parse("2020-01-15T08:00:00Z"))
                .amounts("2", "0")
                .insert(sql);

        as(trader, SUMMARY + "?source=ALL")
                .andExpect(jsonPath("$.from").doesNotExist())
                .andExpect(jsonPath("$.to").value("2026-09-30"))
                .andExpect(jsonPath("$.trades").value(2))
                .andExpect(jsonPath("$.totalNetPnl").value("3.00000000"));
    }

    @Test
    void UC25_summaryWithoutFrom_endsAtTo() throws Exception {
        UUID trader = account(UserRole.TRADER);
        trade(trader).amounts("1", "0").insert(sql);
        TradeRow.closed(trader, pair, Instant.parse("2020-01-15T08:00:00Z"))
                .amounts("2", "0")
                .insert(sql);

        as(trader, SUMMARY + "?source=ALL&to=2021-01-01")
                .andExpect(jsonPath("$.to").value("2021-01-01"))
                .andExpect(jsonPath("$.totalNetPnl").value("2.00000000"));
    }

    @Test
    void UC25_fromWithoutTo_countsUpToToday() throws Exception {
        UUID trader = account(UserRole.TRADER);
        trade(trader).amounts("1", "0").insert(sql);

        as(trader, SUMMARY + "?source=ALL&from=2026-09-01")
                .andExpect(jsonPath("$.from").value("2026-09-01"))
                .andExpect(jsonPath("$.to").value("2026-09-30"))
                .andExpect(jsonPath("$.trades").value(1));
    }

    @Test
    void UC25_period_countsFromAndNotTo() throws Exception {
        UUID trader = account(UserRole.TRADER);
        trade(trader).amounts("1", "0").insert(sql);

        as(trader, SUMMARY + "?source=ALL&from=2026-09-20&to=2026-09-21")
                .andExpect(jsonPath("$.trades").value(1));
        as(trader, SUMMARY + "?source=ALL&from=2026-09-19&to=2026-09-20")
                .andExpect(jsonPath("$.trades").value(0));
    }

    /** 2026-09-19 17:00 UTC is 2026-09-20 00:00 in UTC+7, so the exit belongs to the 20th of the client. */
    @Test
    void UC25_exitAtMidnightUtcPlus7_countsOnThatDay() throws Exception {
        UUID paid = subscribed(PlanTier.PRO);
        TradeRow.closed(paid, pair, MIDNIGHT).amounts("1", "0").insert(sql);

        as(paid, SUMMARY + "?source=ALL&from=2026-09-20&to=2026-09-21")
                .andExpect(jsonPath("$.trades").value(1));
        as(paid, SUMMARY + "?source=ALL&from=2026-09-19&to=2026-09-20")
                .andExpect(jsonPath("$.trades").value(0));
        as(paid, BASE + "/equity-curve?source=ALL")
                .andExpect(jsonPath("$[0].day").value("2026-09-20"));
    }

    @Test
    void UC25_aPeriodLongerThanAYear_isAccepted() throws Exception {
        as(account(UserRole.TRADER), SUMMARY + "?from=2020-01-01&to=2026-01-02").andExpect(status().isOk());
    }

    /** Each answers 400 MSG01, with the problem under the field's name and never the binder's text. */
    @ParameterizedTest
    @CsvSource({
        "?from=notadate, from, is invalid",
        "?to=notadate, to, is invalid",
        "?market=BOTH, market, is invalid",
        "?source=DEMO, source, is invalid",
        "?pairId=1, pairId, is invalid",
        "?strategyId=1, strategyId, is invalid",
        "?from=2026-09-20&to=2026-09-20, from, MSG15",
        "?from=2026-09-21&to=2026-09-20, from, MSG15",
        "?from=2026-09-30, from, MSG15",
        "?from=2030-01-01, from, MSG15",
        "?from=0000-12-31, from, MSG15",
        "?from=-5000-01-01, from, MSG15",
        "?from=+10000-01-01, from, MSG15",
        "?to=0000-12-31, to, MSG15",
        "?to=+1000000-01-01, to, MSG15",
    })
    void UC25_badFilter_namesTheField(String query, String field, String message) throws Exception {
        as(account(UserRole.TRADER), SUMMARY + query)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors." + field).value(message));
    }

    /** The last day a default period can start on, and the widest years accepted (D-139). */
    @ParameterizedTest
    @ValueSource(strings = {"?from=2026-09-29", "?from=0001-01-01&to=9999-12-31"})
    void UC25_periodAtItsLimits_isAccepted(String query) throws Exception {
        as(account(UserRole.TRADER), SUMMARY + query).andExpect(status().isOk());
    }

    /** A pair or strategy nobody traded, or only another Trader did, is an empty result and not an error. */
    @ParameterizedTest
    @ValueSource(strings = {"pairId", "strategyId"})
    void UC25_filterOnAnIdWithoutTradesOfTheCaller_answersEmpty(String filter) throws Exception {
        UUID paid = subscribed(PlanTier.PRO);
        trade(account(UserRole.TRADER)).strategy(BREAKOUT).amounts("9", "0").insert(sql);
        String foreign = filter.equals("pairId") ? pair.toString() : BREAKOUT.toString();

        for (String id : List.of(foreign, UUID.randomUUID().toString())) {
            String query = "?source=ALL&" + filter + "=" + id;
            as(paid, SUMMARY + query)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trades").value(0));
            as(paid, BASE + "/by-coin" + query)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            as(paid, BASE + "/by-strategy" + query)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    void D97_breakdownNet_includesTheSignedFunding() throws Exception {
        UUID paid = subscribed(PlanTier.PRO);
        trade(paid).futures("LONG").amounts("10", "1").funding("-2").insert(sql);

        as(paid, BASE + "/by-coin?source=ALL").andExpect(jsonPath("$[0].netPnl").value("7.00000000"));
        as(paid, BASE + "/by-market?source=ALL")
                .andExpect(jsonPath("$[0].netPnl").value("7.00000000"));
    }

    @Test
    void UC25_breakdownsCarryLabels() throws Exception {
        UUID paid = subscribed(PlanTier.PRO);
        trade(paid).amounts("3", "0").strategy(BREAKOUT).insert(sql);
        trade(paid).futures("LONG").amounts("-1", "0").insert(sql);

        as(paid, BASE + "/by-coin?source=ALL")
                .andExpect(jsonPath("$[0].key").value(pair.toString()))
                .andExpect(jsonPath("$[0].label").value(symbol))
                .andExpect(jsonPath("$[0].trades").value(2))
                .andExpect(jsonPath("$[0].winRate").value("0.50000000"))
                .andExpect(jsonPath("$[0].netPnl").value("2.00000000"))
                .andExpect(jsonPath("$[0].profitFactor").value("3.00000000"));
        as(paid, BASE + "/by-strategy?source=ALL")
                .andExpect(jsonPath("$[0].key").value(BREAKOUT.toString()))
                .andExpect(jsonPath("$[0].label").value("Breakout"))
                .andExpect(jsonPath("$[1].key").doesNotExist())
                .andExpect(jsonPath("$[1].label").value("Unassigned"));
        as(paid, BASE + "/by-market?source=ALL")
                .andExpect(jsonPath("$[0].key").value("SPOT"))
                .andExpect(jsonPath("$[0].label").value("SPOT"))
                .andExpect(jsonPath("$[1].key").value("FUTURES"));
    }

    @Test
    void UC25_equityCurve_answersDaysAndCumulativeNet() throws Exception {
        UUID paid = subscribed(PlanTier.PREMIUM);
        trade(paid).amounts("3", "0").insert(sql);
        TradeRow.closed(paid, pair, EXIT.plusSeconds(24 * 3600))
                .amounts("-1", "0")
                .insert(sql);

        as(paid, BASE + "/equity-curve?source=ALL")
                .andExpect(jsonPath("$[0].day").value("2026-09-20"))
                .andExpect(jsonPath("$[0].trades").value(1))
                .andExpect(jsonPath("$[0].netPnl").value("3.00000000"))
                .andExpect(jsonPath("$[0].cumulativeNetPnl").value("3.00000000"))
                .andExpect(jsonPath("$[1].day").value("2026-09-21"))
                .andExpect(jsonPath("$[1].cumulativeNetPnl").value("2.00000000"));
    }

    private TradeRow trade(UUID trader) {
        return TradeRow.closed(trader, pair, EXIT);
    }

    private UUID subscribed(PlanTier tier) {
        UUID trader = account(UserRole.TRADER);
        traders.subscribe(trader, tier);
        return trader;
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(FixedClockConfig.NOW);
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""")
                .params(id, id + "@performance.invalid", role.name(), at, at)
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
