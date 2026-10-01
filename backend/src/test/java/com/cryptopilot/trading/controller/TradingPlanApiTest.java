package com.cryptopilot.trading.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.dto.request.CreateTradingPlanRequest;
import com.cryptopilot.trading.model.PlanListQuery;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.service.TradingPlanService;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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

/**
 * The trading plan API end to end: security, validation, the plan limits of BR-62 against the seeded packages, the
 * BR-29 thresholds V3 seeded, the D-63 lock under two concurrent activations and the statements a list costs.
 *
 * <p>Nothing is rolled back: the concurrency test needs two committed transactions, so every test writes its own
 * Traders, pair and plans and {@link #removeRows()} deletes them. LIMIT plans only: a MARKET entry needs a streamed
 * price, which {@code TradingPlanServiceImplTest} covers.
 *
 * <p>Rule: UC-16 to UC-20; BR-62; SRS 3.1.3; CR-04; D-63.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TradingPlanApiTest {

    private static final String PLANS = "/api/v1/plans";

    /** The PRO package of 30 days that V11 seeded. */
    private static final UUID PRO_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000003");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private TradingPlanService service;

    @Autowired
    private EntityManagerFactory emf;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID pair;

    @BeforeEach
    void insertPair() {
        String symbol = "TP" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5,
                               futures_tick_size = 0.01, futures_step_size = 0.001, futures_min_notional = 5
                         where pair_id = ?""").param(pair).update();
        sql.sql("""
                        insert into leverage_bracket (pair_id, bracket_no, notional_floor, notional_cap, max_leverage,
                                                      maintenance_margin_rate, maintenance_amount, updated_at)
                        values (?, 1, 0, 50000, 125, 0.004, 0, ?)""").params(pair, now()).update();
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from trading_plan where user_id = ?").param(account).update();
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(account).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from user_profile where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        sql.sql("delete from trading_plan where pair_id = ?").param(pair).update();
        sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
    }

    // ------------------------------------------------------------------------------------------
    // Creating and reading (UC-16, SCR-17, SCR-18)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC16_aPlan_isCreatedAsADraftAndReadBackWithItsSnapshotAndHistory() throws Exception {
        UUID trader = trader();

        String id = id(as(trader, post(PLANS), spotLimit("100", "95", "110", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.snapshot.positionQuantity").value("2.000000000000"))
                .andExpect(jsonPath("$.snapshot.riskAmount").value("10.00000000"))
                .andExpect(jsonPath("$.statusHistory[0].status").value("DRAFT")));

        as(trader, get(PLANS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.capital").value("1000.00000000"))
                .andExpect(jsonPath("$.warnings").isArray());
    }

    @Test
    void SRS351_thePanel_isCalculatedWithoutSavingAnything() throws Exception {
        UUID trader = trader();

        as(trader, post(PLANS + "/calculate"), spotLimit("100", "95", "110", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.notionalValue").value("200.00000000"))
                .andExpect(jsonPath("$.blocksActivation").value(false));

        assertThat(plansOf(trader)).isZero();
    }

    @Test
    void MSG01_aMissingValue_isNamedUnderErrors() throws Exception {
        as(
                        trader(),
                        post(PLANS),
                        "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"direction\": \"LONG\","
                                + " \"entryType\": \"LIMIT\", \"entryPrice\": 100, \"takeProfit\": 110}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.stopLoss").value("MSG01"));
    }

    @Test
    void MSG16_aPriceOrderAgainstTheDirection_isNamedUnderErrors() throws Exception {
        as(trader(), post(PLANS), spotLimit("100", "101", "110", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.stopLoss").value("MSG16"));
    }

    @Test
    void MSG41_anotherTradersPlan_isNotFound() throws Exception {
        String id = id(as(trader(), post(PLANS), spotLimit("100", "95", "110", false)));
        UUID other = trader();

        as(other, get(PLANS + "/" + id), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        as(other, post(PLANS + "/" + id + "/cancel"), null).andExpect(status().isNotFound());
        as(other, put(PLANS + "/" + id), update("96")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------
    // Access (SRS 3.1.3)
    // ------------------------------------------------------------------------------------------

    @Test
    void SRS313_withoutASession_thePlansAnswer401() throws Exception {
        mvc.perform(get(PLANS)).andExpect(status().isUnauthorized());
    }

    @Test
    void SRS313_anAdministrator_isRefusedThePlans() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        mvc.perform(get(PLANS).header(HttpHeaders.AUTHORIZATION, bearer(admin, UserRole.ADMIN)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------
    // Plan entitlements (BR-62)
    // ------------------------------------------------------------------------------------------

    @Test
    void MSG29_aFreeTrader_cannotPlanOnFutures() throws Exception {
        as(trader(), post(PLANS), futuresLimit(false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_FEATURE_NOT_INCLUDED"))
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
    }

    @Test
    void BR62_aProTrader_plansOnFuturesWithMarginAndLiquidation() throws Exception {
        UUID trader = trader();
        subscribeToPro(trader);

        as(trader, post(PLANS), futuresLimit(true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.marginMode").value("ISOLATED"))
                .andExpect(jsonPath("$.snapshot.initialMargin").value("40.00000000"))
                .andExpect(jsonPath("$.snapshot.estimatedLiquidationPrice").isNotEmpty());
    }

    @Test
    void MSG27_aFreeTraderWithThreeActivePlans_cannotActivateAFourth() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < 3; i++) {
            as(trader, post(PLANS), spotLimit("100", "95", "110", true)).andExpect(status().isCreated());
        }
        String fourth = id(as(trader, post(PLANS), spotLimit("100", "95", "110", false)));

        as(trader, post(PLANS + "/" + fourth + "/activate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"))
                .andExpect(jsonPath("$.messageCode").value("MSG27"))
                .andExpect(jsonPath("$.messageArgs[0]").value("3"))
                .andExpect(jsonPath("$.messageArgs[2]").value("FREE"));
        as(trader, get(PLANS + "/" + fourth), null)
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void BR62_aProTrader_hasNoLimitOfActivePlans() throws Exception {
        UUID trader = trader();
        subscribeToPro(trader);

        for (int i = 0; i < 5; i++) {
            as(trader, post(PLANS), spotLimit("100", "95", "110", true))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Activation, editing, cancelling (UC-17 to UC-19)
    // ------------------------------------------------------------------------------------------

    @Test
    void MSG18_aBlockingWarning_refusesTheActivation() throws Exception {
        UUID trader = trader();
        // Risk 10 % of 100 over a stop of 5 buys 2 units: a notional of 200 above the capital (BR-25).
        String blocked = "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"direction\": \"LONG\","
                + " \"entryType\": \"LIMIT\", \"entryPrice\": 100, \"stopLoss\": 95, \"takeProfit\": 110,"
                + " \"capital\": 100, \"riskPercent\": 10}";
        String id = id(as(trader, post(PLANS), blocked)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocksActivation").value(true)));

        as(trader, post(PLANS + "/" + id + "/activate"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRADING_BLOCKING_WARNING"))
                .andExpect(jsonPath("$.messageCode").value("MSG18"));
    }

    @Test
    void UC17_aDraft_isEditedAndThenActivated() throws Exception {
        UUID trader = trader();
        String id = id(as(trader, post(PLANS), spotLimit("100", "95", "110", false)));

        as(trader, put(PLANS + "/" + id), update("96"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopLoss").value("96.00"));
        as(trader, post(PLANS + "/" + id + "/activate"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.statusHistory[1].status").value("ACTIVE"));
        as(trader, put(PLANS + "/" + id), update("97"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRADING_PLAN_STATUS_TRANSITION_INVALID"));
    }

    @Test
    void UC19_aPlan_isCancelledOnce() throws Exception {
        UUID trader = trader();
        String id = id(as(trader, post(PLANS), spotLimit("100", "95", "110", true)));

        as(trader, post(PLANS + "/" + id + "/cancel"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        as(trader, post(PLANS + "/" + id + "/cancel"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRADING_PLAN_STATUS_TRANSITION_INVALID"));
    }

    // ------------------------------------------------------------------------------------------
    // The list (SCR-16, CR-04)
    // ------------------------------------------------------------------------------------------

    @Test
    void CR04_theList_isPagedNewestFirstAndFilteredByTab() throws Exception {
        UUID trader = trader();
        String first = id(as(trader, post(PLANS), spotLimit("100", "95", "110", false)));
        String second = id(as(trader, post(PLANS), spotLimit("100", "95", "110", true)));
        String third = id(as(trader, post(PLANS), spotLimit("100", "95", "110", false)));

        as(trader, get(PLANS + "?pageSize=2"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(third))
                .andExpect(jsonPath("$.items[1].id").value(second));
        as(trader, get(PLANS + "?tab=DRAFT&market=SPOT&pairId=" + pair), null)
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.items[1].id").value(first));
        as(trader, get(PLANS + "?tab=NOT_A_TAB"), null).andExpect(status().isBadRequest());
    }

    /** A page costs the page and its count, the warnings never; a plan's detail costs one more for its warnings. */
    @Test
    void theList_costsOneStatementAndACount_andTheDetailOneMoreForTheWarnings() {
        UUID trader = trader();
        for (int i = 0; i < 3; i++) {
            service.create(trader, request(true));
        }
        UUID id = service.create(trader, request(false)).id();
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        assertThat(service.list(trader, new PlanListQuery(null, null, null, null, null, 1, 2))
                        .items())
                .hasSize(2);
        assertThat(statistics.getPrepareStatementCount())
                .as("the page and its count")
                .isEqualTo(2L);

        statistics.clear();
        service.get(trader, id);
        assertThat(statistics.getPrepareStatementCount())
                .as("the plan and its warnings")
                .isEqualTo(2L);
    }

    // ------------------------------------------------------------------------------------------
    // The D-63 lock
    // ------------------------------------------------------------------------------------------

    /**
     * A FREE Trader at two ACTIVE plans activates two drafts at once: the lock lets one count and activate before the
     * other counts, so exactly one is refused with MSG27 and the Trader ends with three, never four.
     */
    @RepeatedTest(20)
    void D63_twoConcurrentActivationsAtTheLimitMinusOne_letExactlyOneThrough() throws Exception {
        UUID trader = trader();
        service.create(trader, request(true));
        service.create(trader, request(true));
        UUID draftA = service.create(trader, request(false)).id();
        UUID draftB = service.create(trader, request(false)).id();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = List.of(
                    pool.submit(activation(trader, draftA, start)), pool.submit(activation(trader, draftB, start)));
            start.countDown();

            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder("ACTIVE", ErrorCode.PLAN_LIMIT_REACHED.code());
        } finally {
            pool.shutdownNow();
        }
        assertThat(sql.sql("select count(*) from trading_plan where user_id = ? and plan_status = 'ACTIVE'")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
    }

    private Callable<String> activation(UUID trader, UUID plan, CountDownLatch start) {
        return () -> {
            start.await();
            try {
                return service.activate(trader, plan).status().name();
            } catch (BusinessException e) {
                return e.errorCode().code();
            }
        };
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private ResultActions as(
            UUID trader,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader, UserRole.TRADER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String spotLimit(String entry, String stop, String takeProfit, boolean activate) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"direction\": \"LONG\", \"entryType\": \"LIMIT\","
                + " \"entryPrice\": " + entry + ", \"stopLoss\": " + stop + ", \"takeProfit\": " + takeProfit
                + ", \"activate\": " + activate + "}";
    }

    private String futuresLimit(boolean activate) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"FUTURES\", \"direction\": \"LONG\","
                + " \"entryType\": \"LIMIT\", \"entryPrice\": 100, \"stopLoss\": 95, \"takeProfit\": 110,"
                + " \"leverage\": 5, \"activate\": " + activate + "}";
    }

    private static String update(String stop) {
        return "{\"market\": \"SPOT\", \"direction\": \"LONG\", \"entryType\": \"LIMIT\", \"entryPrice\": 100,"
                + " \"stopLoss\": " + stop + ", \"takeProfit\": 110}";
    }

    private CreateTradingPlanRequest request(boolean activate) {
        return new CreateTradingPlanRequest(
                pair,
                MarketType.SPOT,
                Direction.LONG,
                EntryType.LIMIT,
                new BigDecimal("100"),
                new BigDecimal("95"),
                new BigDecimal("110"),
                null,
                null,
                null,
                null,
                null,
                activate);
    }

    private long plansOf(UUID trader) {
        return sql.sql("select count(*) from trading_plan where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    /** A FREE Trader on BALANCED (1 %, 5x, 4 %) with a default capital of 1,000. */
    private UUID trader() {
        UUID id = account(UserRole.TRADER);
        sql.sql("""
                        insert into user_profile (user_id, display_name, default_capital, risk_profile, created_at,
                                                  updated_at)
                        values (?, 'Trader', 1000, 'BALANCED', ?, ?)""").params(id, now(), now()).update();
        return id;
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t039.invalid", role.name(), now(), now()).update();
        accounts.add(id);
        return id;
    }

    /** A PAID order for the PRO package and an ACTIVE subscription that started a day ago. */
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
                        "T039-" + order,
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

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
