package com.cryptopilot.trading.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.ComponentScores;
import com.cryptopilot.market.model.StoredIndicators;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.TechnicalIndicatorRepository;
import com.cryptopilot.market.service.PriceCacheService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * The trading plan API end to end: security, validation, the plan limits of BR-62 against the seeded packages, the
 * BR-29 thresholds V3 seeded, the D-63 lock under two concurrent activations and the statements a list costs.
 *
 * <p>Nothing is rolled back: the concurrency test needs two committed transactions, so every test writes its own
 * Traders, pair and plans and {@link #removeRows()} deletes them. No MARKET plans: their entry price is covered by
 * {@code TradingPlanServiceImplTest}; the STOP activation tests stream a Spot last price into the cache.
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

    @Autowired
    private PriceCacheService prices;

    /** A spy, so one test can make the setup score read fail inside the activation's transaction. */
    @MockitoSpyBean
    private TechnicalIndicatorRepository indicators;

    private static final AtomicLong TICKS = new AtomicLong();

    private final List<UUID> accounts = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        symbol = "TP" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
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
        new MarketTestData(sql, Instant.now()).remove(pair);
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

    /**
     * D-116: the panel and the saved plan answer the fee figures of the Spot vector (LONG LIMIT 100 / 98 / 106, risk
     * 1 % of 10,000; Spot maker and taker are both 0.1 %) as decimal strings, and the stored snapshot keeps them
     * (BR-31).
     */
    @Test
    void D116_thePanelAndTheSavedPlan_answerTheFeeFigures() throws Exception {
        UUID trader = trader();
        String body = "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"direction\": \"LONG\","
                + " \"entryType\": \"LIMIT\", \"entryPrice\": 100, \"stopLoss\": 98, \"takeProfit\": 106,"
                + " \"capital\": 10000, \"riskPercent\": 1}";

        as(trader, post(PLANS + "/calculate"), body).andExpect(status().isOk()).andExpectAll(feeFigures());
        String id =
                id(as(trader, post(PLANS), body).andExpect(status().isCreated()).andExpectAll(feeFigures()));
        as(trader, get(PLANS + "/" + id), null).andExpect(status().isOk()).andExpectAll(feeFigures());
    }

    /**
     * D-116: a Futures MARKET whose fees are 20 % of its risk (entry 100,250, SL 99,750) stores HIGH_FEE_SHARE, a
     * WARNING the warning table accepts (V34), and activates anyway.
     */
    @Test
    void D116_aHighFeeShare_isStoredAsAWarning_andNeverBlocks() throws Exception {
        UUID trader = trader();
        subscribeToPro(trader);
        lastPrice(MarketType.FUTURES, "100250");

        as(
                        trader,
                        post(PLANS),
                        "{\"pairId\": \"" + pair + "\", \"market\": \"FUTURES\", \"direction\": \"LONG\","
                                + " \"entryType\": \"MARKET\", \"stopLoss\": 99750, \"takeProfit\": 101750,"
                                + " \"capital\": 10000, \"riskPercent\": 1, \"leverage\": 5, \"activate\": true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.snapshot.feeShareOfRisk").value("0.20000000"))
                .andExpect(jsonPath("$.warnings[?(@.type == 'HIGH_FEE_SHARE')].severity")
                        .value("WARNING"))
                .andExpect(jsonPath("$.warnings[?(@.type == 'HIGH_FEE_SHARE')].message")
                        .value("fees and slippage are 20% of your risk"));
    }

    private static ResultMatcher[] feeFigures() {
        return new ResultMatcher[] {
            jsonPath("$.snapshot.positionQuantity").value("50.000000000000"),
            jsonPath("$.snapshot.estimatedEntryFee").value("5.00000000"),
            jsonPath("$.snapshot.estimatedStopLossExitFee").value("4.90000000"),
            jsonPath("$.snapshot.estimatedTakeProfitExitFee").value("5.30000000"),
            jsonPath("$.snapshot.estimatedSlippage").value("0.00000000"),
            jsonPath("$.snapshot.lossAtStopLoss").value("109.90000000"),
            jsonPath("$.snapshot.feeShareOfRisk").value("0.09900000"),
            jsonPath("$.snapshot.netRiskReward").value("2.63603276")
        };
    }

    /** The panel answers what a save answers: a MARKET plan takes no invalidation price (MSG01). */
    @Test
    void MSG01_thePanel_refusesAMarketPlanWithAnInvalidationPrice_asCreateDoes() throws Exception {
        UUID trader = trader();
        lastPrice("100");
        String body = "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"direction\": \"LONG\","
                + " \"entryType\": \"MARKET\", \"stopLoss\": 95, \"takeProfit\": 110, \"invalidationPrice\": 120}";

        as(trader, post(PLANS + "/calculate"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.invalidationPrice").value("MSG01"));
        as(trader, post(PLANS), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.invalidationPrice").value("MSG01"));
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
    // Plan validity (TP-A; BR-68, BR-69, BR-70; D-111, D-112)
    // ------------------------------------------------------------------------------------------

    @Test
    void D112_aStopPlan_isCreatedWithTheTimeframeOfTheTradersStyleAndItsPlannedEntry() throws Exception {
        UUID trader = trader();
        sql.sql("update user_profile set trading_style = 'SCALPING' where user_id = ?")
                .param(trader)
                .update();

        as(trader, post(PLANS), spotStop("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("STOP"))
                .andExpect(jsonPath("$.entryPrice").value("100.00"))
                .andExpect(jsonPath("$.plannedEntryPrice").value("100.00"))
                .andExpect(jsonPath("$.analysisTimeframe").value("15m"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.invalidationPrice").doesNotExist())
                .andExpect(jsonPath("$.setupScoreAtPlan").doesNotExist());
    }

    @Test
    void BR69_aDeviationLimitAtTheSeededBlockValue_isSaved() throws Exception {
        as(trader(), post(PLANS), spotStop("{\"maxEntryDeviationR\": 0.75, \"analysisTimeframe\": \"1d\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxEntryDeviationR").value("0.75"))
                .andExpect(jsonPath("$.analysisTimeframe").value("1d"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "\"maxEntryDeviationR\": 0       | maxEntryDeviationR",
                "\"maxEntryDeviationR\": -0.1    | maxEntryDeviationR",
                "\"maxEntryDeviationR\": 0.76    | maxEntryDeviationR",
                "\"analysisTimeframe\": \"5m\" | analysisTimeframe",
                "\"invalidationPrice\": 0        | invalidationPrice"
            })
    void MSG15_aValidityValueOutOfRange_isNamedUnderErrors(String value, String field) throws Exception {
        as(trader(), post(PLANS), spotStop("{" + value + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).value("MSG15"));
    }

    @Test
    void BR68_anInvalidatedPlan_isListedInTheCancelledExpiredTabWithItsReason() throws Exception {
        UUID trader = trader();
        String id = id(as(trader, post(PLANS), spotLimit("100", "95", "110", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        sql.sql("""
                        update trading_plan set plan_status = 'INVALIDATED', invalidated_at = now(),
                               invalidation_reason = 'TAKE_PROFIT_REACHED'
                         where plan_id = ?""").param(UUID.fromString(id)).update();

        as(trader, get(PLANS).param("tab", "CANCELLED_EXPIRED"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].status").value("INVALIDATED"))
                .andExpect(jsonPath("$.items[0].invalidationReason").value("TAKE_PROFIT_REACHED"));
        as(trader, get(PLANS + "/" + id), null)
                .andExpect(jsonPath("$.statusHistory[2].status").value("INVALIDATED"));
    }

    /**
     * D-112: an invalidation price on the wrong side of the current last price answers MSG16 on its field. A LONG
     * LIMIT is invalidated by a rise, so its level lies above the last price; a SHORT LIMIT by a fall, below it. Equal
     * to the last price is wrong too. A Spot plan is LONG (BR-21), so SHORT is a Futures plan, here with a Futures last
     * price in the cache as TP-0 will stream it.
     */
    @ParameterizedTest(name = "{0} {1} LIMIT at last {4}, invalidation {5}")
    @CsvSource({
        "SPOT,    LONG,  95,  110, 102, 101",
        "SPOT,    LONG,  95,  110, 102, 102",
        "FUTURES, SHORT, 105, 90,  98,  99",
        "FUTURES, SHORT, 105, 90,  98,  98"
    })
    void MSG16_aWrongSideInvalidationPrice_isNamedUnderErrors(
            MarketType market, String direction, String stop, String takeProfit, String last, String invalidation)
            throws Exception {
        UUID trader = trader();
        if (market == MarketType.FUTURES) {
            subscribeToPro(trader);
        }
        lastPrice(market, last);

        as(
                        trader,
                        post(PLANS),
                        "{\"pairId\": \"" + pair + "\", \"market\": \"" + market + "\", \"direction\": \""
                                + direction + "\", \"entryType\": \"LIMIT\", \"entryPrice\": 100, \"stopLoss\": "
                                + stop + ", \"takeProfit\": " + takeProfit + ", \"invalidationPrice\": " + invalidation
                                + (market == MarketType.FUTURES ? ", \"leverage\": 5" : "") + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.invalidationPrice").value("MSG16"));
        assertThat(plansOf(trader)).isZero();
    }

    /** POSITION reads as SWING (BR-13), so a POSITION Trader's plan without a timeframe is analysed on 4h. */
    @Test
    void D112_aPositionTrader_createsAPlanWithoutATimeframe_andGets4h() throws Exception {
        UUID trader = trader();
        sql.sql("update user_profile set trading_style = 'POSITION' where user_id = ?")
                .param(trader)
                .update();

        as(trader, post(PLANS), spotLimit("100", "95", "110", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisTimeframe").value("4h"));
    }

    @ParameterizedTest(name = "last price {0}")
    @CsvSource({"100", "101"})
    void MSG16_saveAndActivateAStopTheLastPriceAlreadyTriggered_isRefusedOnTheEntryPrice(String last) throws Exception {
        UUID trader = trader();
        lastPrice(last);

        as(trader, post(PLANS), spotStop("{\"activate\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.entryPrice").value("MSG16"));
        assertThat(plansOf(trader)).isZero();
    }

    @Test
    void BR33_saveAndActivateAStopNotYetTriggered_isActiveAndWaits() throws Exception {
        lastPrice("99.99");

        as(trader(), post(PLANS), spotStop("{\"activate\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /**
     * D-111 E2/E3 (TP-0 closes the gap TP-A pinned): a Futures plan's invalidation price and a Futures STOP at
     * activation are checked against the Futures last price, the close of the latest 1-minute candle update, never
     * against the mark price. Mark 100 and last 103 are cached together: an invalidation price of 102 lies on the
     * entry's side of the last price (MSG16) though above the mark price; a LONG STOP at 101 is already triggered by
     * the last price (MSG16) though not by the mark price. On the right side of the last price both are accepted.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "invalidation 102 below last 103 | LIMIT | \"invalidationPrice\": 102, \"activate\": false | 400 | invalidationPrice",
                "invalidation 104 above last 103 | LIMIT | \"invalidationPrice\": 104, \"activate\": false | 201 |",
                "STOP 101 triggered by last 103  | STOP  | \"activate\": true                             | 400 | entryPrice",
                "STOP 104 not triggered by 103   | STOP  | \"activate\": true, \"entryPrice\": 104        | 201 |"
            })
    void D111_aFuturesPlan_isCheckedAgainstTheLastPrice_notTheMarkPrice(
            String name, String entryType, String extra, int httpStatus, String refusedField) throws Exception {
        UUID trader = trader();
        subscribeToPro(trader);
        markPrice("100");
        lastPrice(MarketType.FUTURES, "103");
        String entryPrice = extra.contains("entryPrice") ? "" : " \"entryPrice\": 101,";

        ResultActions response = as(
                trader,
                post(PLANS),
                "{\"pairId\": \"" + pair + "\", \"market\": \"FUTURES\", \"direction\": \"LONG\","
                        + " \"entryType\": \"" + entryType + "\"," + entryPrice + " \"stopLoss\": 95,"
                        + " \"takeProfit\": 110, \"leverage\": 5, " + extra + "}");

        response.andExpect(status().is(httpStatus));
        if (refusedField != null) {
            response.andExpect(jsonPath("$.errors." + refusedField).value("MSG16"));
            assertThat(plansOf(trader)).isZero();
        }
    }

    /**
     * D-111 E2/E3: a Futures MARKET plan activates and fills at the Futures last price, not the mark price cached
     * beside it, and pays taker (E1).
     */
    @Test
    void D111_aFuturesMarketPlan_fillsAtTheLastPrice_notTheMarkPrice() throws Exception {
        UUID trader = trader();
        subscribeToPro(trader);
        markPrice("100.50");
        lastPrice(MarketType.FUTURES, "100.20");

        String id = id(as(
                        trader,
                        post(PLANS),
                        "{\"pairId\": \"" + pair + "\", \"market\": \"FUTURES\", \"direction\": \"LONG\","
                                + " \"entryType\": \"MARKET\", \"stopLoss\": 95, \"takeProfit\": 110,"
                                + " \"leverage\": 5, \"activate\": true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.entryPrice").value(100.2)));
        assertFill(id, "100.20", "TAKER");
    }

    /**
     * D-111 E2/E3: a Futures LIMIT the last price already reached at activation fills at once at the last price, as
     * taker; the mark price, which has not reached it, does not hold it back.
     */
    @Test
    void D111_aFuturesLimitReachedByTheLastPriceAtActivation_fillsAtOnceAtTheLastPrice() throws Exception {
        UUID trader = trader();
        subscribeToPro(trader);
        markPrice("100.50");
        lastPrice(MarketType.FUTURES, "99.80");

        String id = id(as(trader, post(PLANS), futuresLimit(true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED")));
        assertFill(id, "99.80", "TAKER");
    }

    /**
     * D-112 end to end: Save and Activate stores the setup score of the latest closed candle of the preset's timeframe
     * under the Trader's preset (no style: DAY_TRADING v1 on 1h, 35·75 + 30·50 + 15·40 + 20·60 → 59), which the
     * detail then answers.
     */
    @Test
    void BR70_saveAndActivate_storesTheSetupScoreOfTheLatestClosedCandle() throws Exception {
        UUID trader = trader();
        storeLatestClosedSpotComponents(Duration.ofHours(1), "1h");

        String id = id(as(trader, post(PLANS), spotLimit("100", "95", "110", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.setupScoreAtPlan").value("59.00"))
                .andExpect(jsonPath("$.setupScorePreset").value("DAY_TRADING v1")));
        as(trader, get(PLANS + "/" + id), null)
                .andExpect(jsonPath("$.setupScoreAtPlan").value("59.00"))
                .andExpect(jsonPath("$.setupScorePreset").value("DAY_TRADING v1"));
    }

    /**
     * Q-77: a plan analysed on another timeframe than its preset's stores the score of the preset's own timeframe: a
     * DAY Trader (DAY_TRADING, which reads 1h) planning on 4h stores 35·75 + 30·50 + 15·40 + 20·60 → 59 from the 1h
     * candle, not 35·10 + 30·10 + 15·10 + 20·10 → 10 from the 4h one. The analysis timeframe is kept.
     */
    @Test
    void BR70_aPlanOnAnotherTimeframeThanItsPresets_storesTheScoreOfThePresetsTimeframe() throws Exception {
        UUID trader = trader();
        sql.sql("update user_profile set trading_style = 'DAY' where user_id = ?")
                .param(trader)
                .update();
        storeLatestClosedSpotComponents(Duration.ofHours(1), "1h");
        storeLatestClosedSpotComponents(Duration.ofHours(4), "4h", "10.00", "10.00", "10.00", "10.00");

        as(trader, post(PLANS), withTimeframe(spotLimit("100", "95", "110", true), "4h"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.analysisTimeframe").value("4h"))
                .andExpect(jsonPath("$.setupScoreAtPlan").value("59.00"))
                .andExpect(jsonPath("$.setupScorePreset").value("DAY_TRADING v1"));
    }

    /**
     * D-53, D-112: the stored score is the one the Setup Score card of the analysis screen shows the same Trader at the
     * same moment, whichever chart timeframe is open: a SWING Trader (4h) planning on 1h, with the 1h chart open.
     */
    @Test
    void BR70_theStoredScore_isTheOneTheAnalysisScreenShows() throws Exception {
        UUID trader = trader();
        sql.sql("update user_profile set trading_style = 'SWING' where user_id = ?")
                .param(trader)
                .update();
        storeLatestClosedSpotComponents(Duration.ofHours(4), "4h");
        storeLatestClosedSpotComponents(Duration.ofHours(1), "1h", "10.00", "10.00", "10.00", "10.00");

        String card = as(trader, get("/api/v1/analysis/spot/" + symbol + "?tf=1h"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupScore.timeframe").value("4h"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String plan = as(trader, post(PLANS), withTimeframe(spotLimit("100", "95", "110", true), "1h"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisTimeframe").value("1h"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer shown = JsonPath.read(card, "$.setupScore.score");
        String stored = JsonPath.read(plan, "$.setupScoreAtPlan");
        // SWING Spot: 40·75 + 20·50 + 10·40 + 30·60 → 62
        assertThat(shown).isEqualTo(62);
        assertThat(new BigDecimal(stored)).isEqualByComparingTo(BigDecimal.valueOf(shown));
        assertThat(JsonPath.<String>read(plan, "$.setupScorePreset"))
                .isEqualTo(JsonPath.<String>read(card, "$.setupScore.preset") + " "
                        + JsonPath.<String>read(card, "$.setupScore.presetVersion"));
    }

    /**
     * A setup score read that fails with an SQL error inside the activation's transaction is rolled back alone: the
     * plan is activated and committed with neither score nor preset.
     */
    @Test
    void BR70_aSetupScoreReadFailingInTheDatabase_doesNotUndoTheActivation() throws Exception {
        UUID trader = trader();
        String id = id(as(trader, post(PLANS), spotLimit("100", "95", "110", false)));
        doAnswer(call -> sql.sql("select 1 / 0").query(Integer.class).single())
                .when(indicators)
                .at(eq(pair), any(), anyString(), any());

        as(trader, post(PLANS + "/" + id + "/activate"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.setupScoreAtPlan").doesNotExist())
                .andExpect(jsonPath("$.setupScorePreset").doesNotExist());
        assertThat(sql.sql("select plan_status from trading_plan where plan_id = ?")
                        .param(UUID.fromString(id))
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
    }

    /** Nothing analysed on the plan's timeframe: the activation goes on, with a null score beside the preset. */
    @Test
    void BR70_activationWithoutAnAnalysedCandle_storesANullScore_andIsNotBlocked() throws Exception {
        UUID trader = trader();
        String id = id(as(trader, post(PLANS), spotLimit("100", "95", "110", false)));

        as(trader, post(PLANS + "/" + id + "/activate"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.setupScoreAtPlan").doesNotExist())
                .andExpect(jsonPath("$.setupScorePreset").value("DAY_TRADING v1"));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Streams a Futures mark price of the test pair into the price cache, newer than every earlier one. */
    private void markPrice(String mark) {
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

    /** Streams a Spot ticker of the test pair into the price cache, newer than every earlier one. */
    private void lastPrice(String last) {
        lastPrice(MarketType.SPOT, last);
    }

    /**
     * Streams the last price of the test pair on {@code market} into the price cache, newer than every earlier one:
     * a ticker on Spot, a forming 1-minute candle update on Futures, whose close is the last price (D-111 E2/E3).
     */
    private void lastPrice(MarketType market, String last) {
        BigDecimal value = new BigDecimal(last);
        Instant at = Instant.now().plusMillis(TICKS.incrementAndGet());
        if (market == MarketType.FUTURES) {
            Instant open = at.truncatedTo(ChronoUnit.MINUTES);
            prices.record(
                    market,
                    new KlineMessage(
                            symbol,
                            MarketInterval.ONE_MINUTE,
                            new Kline(
                                    open,
                                    open.plusSeconds(60).minusMillis(1),
                                    value,
                                    value,
                                    value,
                                    value,
                                    BigDecimal.ONE,
                                    BigDecimal.ONE,
                                    1),
                            false,
                            at));
            prices.flush();
            return;
        }
        prices.record(
                market,
                new TickerMessage(
                        symbol,
                        value,
                        value,
                        value,
                        value,
                        value,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        at));
        prices.flush();
    }

    /** The plan's stored fill: its price and liquidity role. */
    private void assertFill(String id, String price, String liquidity) {
        Map<String, Object> fill = sql.sql("select fill_price, fill_liquidity from trading_plan where plan_id = ?")
                .param(UUID.fromString(id))
                .query()
                .singleRow();
        assertThat((BigDecimal) fill.get("fill_price")).isEqualByComparingTo(price);
        assertThat(fill.get("fill_liquidity")).isEqualTo(liquidity);
    }

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

    /** Spot components 75, 50, 40, 60 for the latest closed candle of {@code timeframe} by the system clock. */
    private void storeLatestClosedSpotComponents(Duration interval, String timeframe) {
        storeLatestClosedSpotComponents(interval, timeframe, "75.00", "50.00", "40.00", "60.00");
    }

    /** These Spot components for the latest closed candle of {@code timeframe} by the system clock. */
    private void storeLatestClosedSpotComponents(
            Duration interval, String timeframe, String trend, String momentum, String volume, String level) {
        long seconds = interval.toSeconds();
        long now = Instant.now().getEpochSecond();
        Instant closed = Instant.ofEpochSecond(now - now % seconds - seconds);
        indicators.upsert(new StoredIndicators(
                pair,
                closed,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ComponentScores(
                        MarketType.SPOT,
                        timeframe,
                        "v1",
                        new BigDecimal(trend),
                        new BigDecimal(momentum),
                        new BigDecimal(volume),
                        new BigDecimal(level),
                        null),
                closed.plus(interval)));
    }

    /** {@code body} with an analysis timeframe the Trader chose. */
    private static String withTimeframe(String body, String timeframe) {
        return body.substring(0, body.length() - 1) + ", \"analysisTimeframe\": \"" + timeframe + "\"}";
    }

    private String spotLimit(String entry, String stop, String takeProfit, boolean activate) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"direction\": \"LONG\", \"entryType\": \"LIMIT\","
                + " \"entryPrice\": " + entry + ", \"stopLoss\": " + stop + ", \"takeProfit\": " + takeProfit
                + ", \"activate\": " + activate + "}";
    }

    /** A Spot LONG STOP draft at 100 (SL 95, TP 110) with the extra fields of {@code extra}, a JSON object. */
    private String spotStop(String extra) {
        String fields = extra.substring(1, extra.length() - 1).trim();
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"direction\": \"LONG\", \"entryType\": \"STOP\","
                + " \"entryPrice\": 100, \"stopLoss\": 95, \"takeProfit\": 110"
                + (fields.isEmpty() ? "" : ", " + fields) + "}";
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
