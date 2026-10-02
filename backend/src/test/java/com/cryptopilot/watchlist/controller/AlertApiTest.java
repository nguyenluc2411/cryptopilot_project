package com.cryptopilot.watchlist.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.cryptopilot.watchlist.dto.request.CreateAlertRequest;
import com.cryptopilot.watchlist.dto.request.UpdateAlertRequest;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import com.cryptopilot.watchlist.service.AlertService;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The alert API end to end: security, the rule's fields, ownership, the plan's features and limits against the seeded
 * packages, the watchlist row an alert hangs on (BR-16), the status transitions and the D-63 lock under concurrent
 * requests.
 *
 * <p>Nothing is rolled back, because the concurrency tests need committed transactions: every test writes its own
 * Traders and pairs and {@link #removeRows()} deletes them.
 *
 * <p>Rule: UC-13, UC-14; BR-07, BR-16, BR-17, BR-19, BR-62; SRS 3.4.2, 3.4.3; D-63; A-40.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AlertApiTest {

    private static final String ALERTS = "/api/v1/alerts";

    private static final String WATCHLIST = "/api/v1/watchlist";

    /** The PRO package of 30 days that V11 seeded. */
    private static final UUID PRO_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000003");

    /** {@code ACTIVE_ALERT_MAX} of the seeded FREE package (D-58). */
    private static final int FREE_ALERTS = 3;

    /** {@code WATCHLIST_MAX} of the seeded FREE package (D-58). */
    private static final int FREE_WATCHLIST = 5;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private AlertService service;

    private MarketTestData market;
    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private final List<String> symbols = new ArrayList<>();
    private UUID spotOnlyPair;
    private UUID unlistedPair;

    @BeforeEach
    void insertPairs() {
        market = new MarketTestData(sql, Instant.now());
        String run = Long.toString(System.nanoTime() % 1_000_000_000L);
        for (int i = 0; i < 8; i++) {
            String symbol = "AL" + run + (char) ('A' + i) + "USDT";
            pairs.add(market.pair(symbol, true, true, "TRADING", "TRADING", i));
            symbols.add(symbol);
        }
        spotOnlyPair = market.pair("AL" + run + "SUSDT", true, false, "TRADING", null, 8);
        unlistedPair = market.pair("AL" + run + "XUSDT", false, false, null, null, 9);
        List<UUID> all = new ArrayList<>(pairs);
        all.add(spotOnlyPair);
        all.add(unlistedPair);
        for (UUID pair : all) {
            sql.sql("""
                            update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5,
                                   futures_tick_size = 0.1, futures_step_size = 0.001, futures_min_notional = 5
                             where pair_id = ?""").param(pair).update();
        }
    }

    @AfterEach
    void removeRows() {
        for (UUID account : accounts) {
            sql.sql("delete from alert where user_id = ?").param(account).update();
            sql.sql("delete from watchlist where user_id = ?").param(account).update();
            sql.sql("""
                            delete from user_subscription
                             where order_id in (select order_id from subscription_order where user_id = ?)""").param(account).update();
            sql.sql("delete from subscription_order where user_id = ?")
                    .param(account)
                    .update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        List<UUID> all = new ArrayList<>(pairs);
        all.add(spotOnlyPair);
        all.add(unlistedPair);
        for (UUID pair : all) {
            List<UUID> coins = sql.sql("select base_coin_id from crypto_pair where pair_id = ?")
                    .param(pair)
                    .query(UUID.class)
                    .list();
            sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
            for (UUID coin : coins) {
                sql.sql("""
                                delete from coin where coin_id = ?
                                   and not exists (select 1 from crypto_pair
                                                    where base_coin_id = coin.coin_id or quote_coin_id = coin.coin_id)""").param(coin).update();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Creating, listing, editing, pausing, removing (UC-13, UC-14)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC13_aPriceAlertOnAWatchedPair_isCreatedActive_withItsTargetOnTheTick() throws Exception {
        UUID trader = trader();
        String row = watch(trader, pairs.get(0));

        as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "100.005"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.watchlistId").value(row))
                .andExpect(jsonPath("$.pairId").value(pairs.get(0).toString()))
                .andExpect(jsonPath("$.symbol").value(symbols.get(0)))
                .andExpect(jsonPath("$.market").value("SPOT"))
                .andExpect(jsonPath("$.type").value("PRICE"))
                .andExpect(jsonPath("$.indicator").doesNotExist())
                .andExpect(jsonPath("$.timeframe").doesNotExist())
                .andExpect(jsonPath("$.condition").value("CROSS_ABOVE"))
                .andExpect(jsonPath("$.threshold").value(100.01))
                .andExpect(jsonPath("$.triggerMode").value("ONCE"))
                .andExpect(jsonPath("$.notifyInApp").value(true))
                .andExpect(jsonPath("$.notifyEmail").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.triggerCount").value(0))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(watchRows(trader)).isOne();
        assertThat(alertsOf(trader, "ACTIVE")).isOne();
    }

    @Test
    void BR16_anAlertOnAPairNotYetWatched_addsThePairToTheWatchlist() throws Exception {
        UUID trader = trader();

        String created =
                body(as(trader, post(ALERTS), price(pairs.get(1), "SPOT", "5")).andExpect(status().isCreated()));

        assertThat(watchRows(trader)).isOne();
        String row = sql.sql("select watchlist_id::text from watchlist where user_id = ? and pair_id = ?")
                .params(trader, pairs.get(1))
                .query(String.class)
                .single();
        assertThat((String) JsonPath.read(created, "$.watchlistId")).isEqualTo(row);
        as(trader, get(WATCHLIST), null)
                .andExpect(jsonPath("$.items[0].activeAlerts").value(1));
    }

    @Test
    void UC13_eachIndicator_isCreatedForAProTrader_withTheValuesTheServerFixes() throws Exception {
        UUID trader = proTrader();

        as(trader, post(ALERTS), indicator(pairs.get(0), "SPOT", "RSI_14", "15m", "LESS_THAN", "30"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.indicator").value("RSI_14"))
                .andExpect(jsonPath("$.timeframe").value("15m"))
                .andExpect(jsonPath("$.threshold").value(30));
        as(trader, post(ALERTS), indicator(pairs.get(0), "SPOT", "MACD_CROSS", "4h", "CROSS_ABOVE", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeframe").value("4h"))
                .andExpect(jsonPath("$.threshold").value(0));
        as(trader, post(ALERTS), indicator(pairs.get(0), "FUTURES", "EMA_CROSS", "1d", "CROSS_BELOW", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.market").value("FUTURES"))
                .andExpect(jsonPath("$.condition").value("CROSS_BELOW"));
        as(trader, post(ALERTS), indicator(pairs.get(0), "FUTURES", "FUNDING_RATE", null, "GREATER_THAN", "0.01"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeframe").value("1h"))
                .andExpect(jsonPath("$.threshold").value(0.01));
        as(trader, post(ALERTS), indicator(pairs.get(0), "FUTURES", "OPEN_INTEREST_CHANGE", null, "LESS_THAN", "-5"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeframe").value("1h"))
                .andExpect(jsonPath("$.threshold").value(-5));

        assertThat(alertsOf(trader, "ACTIVE")).isEqualTo(5);
        assertThat(watchRows(trader)).isOne();
    }

    @Test
    void UC13_aProTrader_mayAskForEmailAndPushAndAFuturesPriceAlert() throws Exception {
        UUID trader = proTrader();
        Instant expiry = Instant.now().plus(Duration.ofDays(30));

        as(
                        trader,
                        post(ALERTS),
                        json(
                                "pairId",
                                pairs.get(2),
                                "market",
                                "FUTURES",
                                "type",
                                "PRICE",
                                "condition",
                                "LESS_THAN",
                                "threshold",
                                61000.04,
                                "triggerMode",
                                "EVERY_TIME",
                                "cooldownMinutes",
                                15,
                                "notifyEmail",
                                true,
                                "notifyPush",
                                true,
                                "expiresAt",
                                expiry.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.threshold").value(61000.0))
                .andExpect(jsonPath("$.cooldownMinutes").value(15))
                .andExpect(jsonPath("$.notifyEmail").value(true))
                .andExpect(jsonPath("$.notifyPush").value(true))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void UC14_theList_isNewestFirst_filteredByStatusAndType_andPaged() throws Exception {
        UUID trader = proTrader();
        String first = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));
        String second =
                id(as(trader, post(ALERTS), indicator(pairs.get(1), "SPOT", "RSI_14", "1h", "LESS_THAN", "30")));
        String third = id(as(trader, post(ALERTS), price(pairs.get(1), "SPOT", "2")));
        as(trader, post(ALERTS + "/" + third + "/pause"), null).andExpect(status().isOk());

        as(trader, get(ALERTS), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.items[0].id").value(third))
                .andExpect(jsonPath("$.items[2].id").value(first))
                .andExpect(jsonPath("$.items[1].symbol").value(symbols.get(1)));
        as(trader, get(ALERTS).param("status", "ACTIVE"), null)
                .andExpect(jsonPath("$.total").value(2));
        as(trader, get(ALERTS).param("type", "INDICATOR"), null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second));
        as(trader, get(ALERTS).param("status", "PAUSED").param("type", "PRICE"), null)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(third));
        as(trader, get(ALERTS).param("page", "2").param("pageSize", "2"), null)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first));
        as(trader, get(ALERTS).param("page", "0"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page").value("MSG15"));
        as(trader, get(ALERTS).param("pageSize", "0"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").value("MSG15"));
        as(trader, get(ALERTS).param("pageSize", "101"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").value("MSG15"));
    }

    @Test
    void UC14_anEdit_replacesTheRule_andKeepsTheAlertOnItsPair() throws Exception {
        UUID trader = proTrader();
        String alert = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));

        as(trader, put(ALERTS + "/" + alert), indicator(null, "FUTURES", "RSI_14", "4h", "GREATER_THAN", "70"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairId").value(pairs.get(0).toString()))
                .andExpect(jsonPath("$.market").value("FUTURES"))
                .andExpect(jsonPath("$.type").value("INDICATOR"))
                .andExpect(jsonPath("$.indicator").value("RSI_14"))
                .andExpect(jsonPath("$.threshold").value(70))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void UC14_pauseResumeAndDelete_actOnTheCallersAlert() throws Exception {
        UUID trader = trader();
        String alert = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));

        as(trader, post(ALERTS + "/" + alert + "/pause"), null)
                .andExpect(jsonPath("$.status").value("PAUSED"));
        assertThat(alertsOf(trader, "ACTIVE")).isZero();
        as(trader, post(ALERTS + "/" + alert + "/resume"), null)
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        as(trader, delete(ALERTS + "/" + alert), null).andExpect(status().isNoContent());

        assertThat(alertsOf(trader, null)).isZero();
        assertThat(watchRows(trader)).isOne();
    }

    // ------------------------------------------------------------------------------------------
    // The rule's fields (SRS 3.4.2, BR-19, A-40)
    // ------------------------------------------------------------------------------------------

    @Test
    void SRS342_invalidCombinations_areRefusedFieldByField() throws Exception {
        UUID trader = proTrader();

        as(trader, post(ALERTS), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.pairId").value("MSG01"))
                .andExpect(jsonPath("$.errors.market").value("MSG01"))
                .andExpect(jsonPath("$.errors.type").value("MSG01"))
                .andExpect(jsonPath("$.errors.condition").value("MSG01"))
                .andExpect(jsonPath("$.errors.triggerMode").value("MSG01"));
        as(trader, post(ALERTS), indicator(pairs.get(0), "SPOT", "FUNDING_RATE", null, "GREATER_THAN", "0.01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.market").value("MSG01"));
        as(trader, post(ALERTS), indicator(pairs.get(0), "FUTURES", "OPEN_INTEREST_CHANGE", "1h", "GREATER_THAN", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.timeframe").value("MSG01"));
        as(trader, post(ALERTS), indicator(pairs.get(0), "SPOT", "RSI_14", "2h", "GREATER_THAN", "70"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.timeframe").value("MSG01"));
        as(trader, post(ALERTS), indicator(pairs.get(0), "SPOT", "RSI_14", "1h", "GREATER_THAN", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.threshold").value("MSG15"));
        as(trader, post(ALERTS), indicator(pairs.get(0), "SPOT", "MACD_CROSS", "1h", "GREATER_THAN", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.condition").value("MSG01"));
        as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "0.004"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.threshold").value("MSG15"));
        as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.threshold").value("MSG15"));
        as(trader, post(ALERTS), rule(pairs.get(0), "EVERY_TIME", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cooldownMinutes").value("MSG01"));
        as(trader, post(ALERTS), rule(pairs.get(0), "EVERY_TIME", 0, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cooldownMinutes").value("MSG15"));
        as(trader, post(ALERTS), rule(pairs.get(0), "ONCE", null, Instant.now().plus(Duration.ofDays(91))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresAt").value("MSG15"));
        as(trader, post(ALERTS), rule(pairs.get(0), "ONCE", null, Instant.now().minusSeconds(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresAt").value("MSG15"));

        assertThat(alertsOf(trader, null)).isZero();
        assertThat(watchRows(trader)).isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Ownership and the pair (OWASP API1, BR-07)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC14_anotherTradersAlert_isNeitherListedNorChangedNorRemoved() throws Exception {
        UUID owner = trader();
        UUID other = trader();
        String alert = id(as(owner, post(ALERTS), price(pairs.get(0), "SPOT", "1")));

        as(other, get(ALERTS), null).andExpect(jsonPath("$.total").value(0));
        as(other, put(ALERTS + "/" + alert), price(null, "SPOT", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(other, post(ALERTS + "/" + alert + "/pause"), null).andExpect(status().isNotFound());
        as(other, post(ALERTS + "/" + alert + "/resume"), null).andExpect(status().isNotFound());
        as(other, delete(ALERTS + "/" + alert), null).andExpect(status().isNotFound());

        as(owner, get(ALERTS), null)
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].threshold").value("1.000000000000"));
    }

    @Test
    void BR07_aPairNotEnabledOnTheMarket_cannotCarryANewAlert() throws Exception {
        UUID trader = proTrader();

        as(trader, post(ALERTS), price(unlistedPair, "SPOT", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(trader, post(ALERTS), price(spotOnlyPair, "FUTURES", "1")).andExpect(status().isNotFound());
        as(trader, post(ALERTS), price(UUID.randomUUID(), "SPOT", "1")).andExpect(status().isNotFound());

        assertThat(watchRows(trader)).isZero();
    }

    @Test
    void Q29_aPairDisabledLater_refusesResumeAndReactivation_butKeepsTheAlerts() throws Exception {
        UUID trader = trader();
        String paused = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));
        String triggered = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "2")));
        as(trader, post(ALERTS + "/" + paused + "/pause"), null).andExpect(status().isOk());
        setStatus(triggered, "TRIGGERED");
        market.enable(pairs.get(0), false, false);

        as(trader, post(ALERTS + "/" + paused + "/resume"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(trader, put(ALERTS + "/" + triggered), price(null, "SPOT", "3")).andExpect(status().isNotFound());

        assertThat(statusOf(paused)).isEqualTo("PAUSED");
        assertThat(statusOf(triggered)).isEqualTo("TRIGGERED");
        as(trader, get(ALERTS), null).andExpect(jsonPath("$.total").value(2));
        as(trader, post(ALERTS + "/" + paused + "/pause"), null).andExpect(status().isConflict());
        as(trader, delete(ALERTS + "/" + paused), null).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------------------------------
    // Plan features and limits (BR-17, BR-62)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR62_aFreeTrader_isRefusedIndicatorFuturesAndExternalAlerts_withMsg29() throws Exception {
        UUID trader = trader();

        as(trader, post(ALERTS), indicator(pairs.get(0), "SPOT", "RSI_14", "1h", "LESS_THAN", "30"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
        as(trader, post(ALERTS), price(pairs.get(0), "FUTURES", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
        as(
                        trader,
                        post(ALERTS),
                        json(
                                "pairId", pairs.get(0),
                                "market", "SPOT",
                                "type", "PRICE",
                                "condition", "CROSS_ABOVE",
                                "threshold", 1,
                                "triggerMode", "ONCE",
                                "notifyPush", true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));

        assertThat(alertsOf(trader, null)).isZero();
        assertThat(watchRows(trader)).isZero();
    }

    @Test
    void BR17_aFreeTraderAtThreeActiveAlerts_isRefusedTheFourthWithMsg27() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < FREE_ALERTS; i++) {
            as(trader, post(ALERTS), price(pairs.get(0), "SPOT", String.valueOf(i + 1)))
                    .andExpect(status().isCreated());
        }

        as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "9"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"))
                .andExpect(jsonPath("$.messageCode").value("MSG27"))
                .andExpect(jsonPath("$.messageArgs[0]").value(String.valueOf(FREE_ALERTS)))
                .andExpect(jsonPath("$.messageArgs[1]").value("active alerts"))
                .andExpect(jsonPath("$.messageArgs[2]").value("FREE"));
        assertThat(alertsOf(trader, "ACTIVE")).isEqualTo(FREE_ALERTS);
    }

    @Test
    void BR17_pausedAlertsDoNotCount_butResumingOrReactivatingAtTheLimitIsRefused() throws Exception {
        UUID trader = trader();
        String paused = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));
        as(trader, post(ALERTS + "/" + paused + "/pause"), null).andExpect(status().isOk());
        String triggered = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "2")));
        setStatus(triggered, "TRIGGERED");
        List<String> active = new ArrayList<>();
        for (int i = 0; i < FREE_ALERTS; i++) {
            active.add(id(as(trader, post(ALERTS), price(pairs.get(1), "SPOT", String.valueOf(i + 3)))));
        }

        as(trader, post(ALERTS + "/" + paused + "/resume"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG27"));
        as(trader, put(ALERTS + "/" + triggered), price(null, "SPOT", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG27"));
        // An edit that does not add an ACTIVE alert is not counted.
        as(trader, put(ALERTS + "/" + active.getFirst()), price(null, "SPOT", "7"))
                .andExpect(status().isOk());
        as(trader, put(ALERTS + "/" + paused), price(null, "SPOT", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        assertThat(statusOf(triggered)).isEqualTo("TRIGGERED");
        assertThat(alertsOf(trader, "ACTIVE")).isEqualTo(FREE_ALERTS);
    }

    @Test
    void BR16_aFullWatchlist_refusesAnAlertOnANewPair_andCreatesNothing() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < FREE_WATCHLIST; i++) {
            watch(trader, pairs.get(i));
        }

        as(trader, post(ALERTS), price(pairs.get(FREE_WATCHLIST), "SPOT", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG27"))
                .andExpect(jsonPath("$.messageArgs[1]").value("watchlist items"));
        as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")).andExpect(status().isCreated());

        assertThat(watchRows(trader)).isEqualTo(FREE_WATCHLIST);
        assertThat(alertsOf(trader, null)).isOne();
    }

    @Test
    void BR16_anAlertRefusedByItsLimit_leavesNoWatchlistRowBehind() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < FREE_ALERTS; i++) {
            as(trader, post(ALERTS), price(pairs.get(0), "SPOT", String.valueOf(i + 1)))
                    .andExpect(status().isCreated());
        }

        as(trader, post(ALERTS), price(pairs.get(1), "SPOT", "1")).andExpect(status().isConflict());

        assertThat(watchRows(trader)).isOne();
    }

    // ------------------------------------------------------------------------------------------
    // Status transitions (SRS 3.4.3)
    // ------------------------------------------------------------------------------------------

    @Test
    void SRS343_transitionsTheTableDoesNotList_areRefusedWith409() throws Exception {
        UUID trader = trader();
        String alert = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));

        as(trader, post(ALERTS + "/" + alert + "/resume"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALERT_STATUS_TRANSITION_INVALID"));
        as(trader, post(ALERTS + "/" + alert + "/pause"), null).andExpect(status().isOk());
        as(trader, post(ALERTS + "/" + alert + "/pause"), null).andExpect(status().isConflict());

        for (String engineStatus : List.of("TRIGGERED", "EXPIRED")) {
            setStatus(alert, engineStatus);
            as(trader, post(ALERTS + "/" + alert + "/pause"), null).andExpect(status().isConflict());
            as(trader, post(ALERTS + "/" + alert + "/resume"), null).andExpect(status().isConflict());
            assertThat(statusOf(alert)).isEqualTo(engineStatus);
        }
    }

    @Test
    void SRS343_theStatusInABody_isIgnored_soTheApiNeverSetsTriggeredOrExpired() throws Exception {
        UUID trader = trader();
        String alert = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));
        Map<String, Object> body = new LinkedHashMap<>(rule(null, "SPOT", "PRICE", null, null, "CROSS_ABOVE", "2"));
        body.put("status", "TRIGGERED");

        as(trader, put(ALERTS + "/" + alert), json(body))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        body.put("status", "EXPIRED");
        body.put("pairId", pairs.get(0));
        as(trader, post(ALERTS), json(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(alertsOf(trader, "ACTIVE")).isEqualTo(2);
    }

    @Test
    void SRS343_aTriggeredOrExpiredAlert_isActiveAgainAfterAnEdit() throws Exception {
        UUID trader = trader();
        String triggered = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));
        String expired = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "2")));
        setStatus(triggered, "TRIGGERED");
        setStatus(expired, "EXPIRED");

        as(trader, put(ALERTS + "/" + triggered), price(null, "SPOT", "3"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        as(trader, put(ALERTS + "/" + expired), price(null, "SPOT", "4"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void SRS343_aPausedAlertPastItsExpiry_isNotResumed() throws Exception {
        UUID trader = trader();
        String alert = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1")));
        as(trader, post(ALERTS + "/" + alert + "/pause"), null).andExpect(status().isOk());
        sql.sql("update alert set expires_at = ? where alert_id = ?")
                .params(Timestamp.from(Instant.now().minusSeconds(60)), UUID.fromString(alert))
                .update();

        as(trader, post(ALERTS + "/" + alert + "/resume"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresAt").value("MSG15"));
        assertThat(statusOf(alert)).isEqualTo("PAUSED");
    }

    @Test
    void BR62_aPausedIndicatorAlert_isNotResumedOnAPlanWithoutIndicatorAlerts() throws Exception {
        UUID trader = trader();
        String row = watch(trader, pairs.get(0));
        UUID alert = UUID.randomUUID();
        sql.sql("""
                        insert into alert (alert_id, user_id, watchlist_id, market_type, alert_type, indicator_name,
                                           timeframe, condition_operator, threshold_value, trigger_mode, alert_status,
                                           created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'INDICATOR', 'RSI_14', '1h', 'LESS_THAN', 30, 'ONCE', 'PAUSED', ?, ?)""").params(alert, trader, UUID.fromString(row), now(), now()).update();

        as(trader, post(ALERTS + "/" + alert + "/resume"), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
    }

    // ------------------------------------------------------------------------------------------
    // Removing the watched pair (BR-16, MSG26)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR16_removingAWatchedPair_asksMsg26WithItsAlertCount_andThenTakesThemWithIt() throws Exception {
        UUID trader = trader();
        String row = JsonPath.read(body(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "1"))), "$.watchlistId");
        String paused = id(as(trader, post(ALERTS), price(pairs.get(0), "SPOT", "2")));
        as(trader, post(ALERTS + "/" + paused + "/pause"), null).andExpect(status().isOk());
        as(trader, post(ALERTS), price(pairs.get(1), "SPOT", "3")).andExpect(status().isCreated());

        as(trader, delete(WATCHLIST + "/" + row), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG26"))
                .andExpect(jsonPath("$.messageArgs[0]").value(symbols.get(0)))
                .andExpect(jsonPath("$.messageArgs[1]").value("2"));
        assertThat(alertsOf(trader, null)).isEqualTo(3);

        as(trader, delete(WATCHLIST + "/" + row + "?confirm=true"), null).andExpect(status().isNoContent());
        assertThat(alertsOf(trader, null)).isOne();
        as(trader, get(ALERTS), null).andExpect(jsonPath("$.items[0].symbol").value(symbols.get(1)));
    }

    // ------------------------------------------------------------------------------------------
    // Concurrency (D-63)
    // ------------------------------------------------------------------------------------------

    @RepeatedTest(10)
    void D63_twoConcurrentCreatesAtTheLimitMinusOne_letExactlyOneThrough() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < FREE_ALERTS - 1; i++) {
            service.create(trader, priceRequest(pairs.get(0), i + 1));
        }

        List<String> outcomes = concurrently(List.of(
                () -> service.create(trader, priceRequest(pairs.get(0), 8)),
                () -> service.create(trader, priceRequest(pairs.get(0), 9))));

        assertThat(outcomes).containsExactlyInAnyOrder("DONE", "PLAN_LIMIT_REACHED");
        assertThat(alertsOf(trader, "ACTIVE")).isEqualTo(FREE_ALERTS);
    }

    @RepeatedTest(10)
    void D63_twoConcurrentCreatesOnNewPairsAtTheWatchlistLimitMinusOne_addExactlyOneRow() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < FREE_WATCHLIST - 1; i++) {
            watch(trader, pairs.get(i));
        }

        List<String> outcomes = concurrently(List.of(
                () -> service.create(trader, priceRequest(pairs.get(FREE_WATCHLIST - 1), 1)),
                () -> service.create(trader, priceRequest(pairs.get(FREE_WATCHLIST), 1))));

        assertThat(outcomes).containsExactlyInAnyOrder("DONE", "PLAN_LIMIT_REACHED");
        assertThat(watchRows(trader)).isEqualTo(FREE_WATCHLIST);
        assertThat(alertsOf(trader, null)).isOne();
    }

    @RepeatedTest(10)
    void D63_twoConcurrentResumesAtTheLimitMinusOne_letExactlyOneThrough() throws Exception {
        UUID trader = trader();
        List<UUID> paused = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            UUID alert =
                    service.create(trader, priceRequest(pairs.get(0), i + 1)).id();
            service.pause(trader, alert);
            paused.add(alert);
        }
        for (int i = 0; i < FREE_ALERTS - 1; i++) {
            service.create(trader, priceRequest(pairs.get(1), i + 1));
        }

        List<String> outcomes = concurrently(
                List.of(() -> service.resume(trader, paused.get(0)), () -> service.resume(trader, paused.get(1))));

        assertThat(outcomes).containsExactlyInAnyOrder("DONE", "PLAN_LIMIT_REACHED");
        assertThat(alertsOf(trader, "ACTIVE")).isEqualTo(FREE_ALERTS);
    }

    @RepeatedTest(10)
    void D63_twoConcurrentReactivationsAtTheLimitMinusOne_letExactlyOneThrough() throws Exception {
        UUID trader = trader();
        List<UUID> triggered = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            UUID alert =
                    service.create(trader, priceRequest(pairs.get(0), i + 1)).id();
            setStatus(alert.toString(), "TRIGGERED");
            triggered.add(alert);
        }
        for (int i = 0; i < FREE_ALERTS - 1; i++) {
            service.create(trader, priceRequest(pairs.get(1), i + 1));
        }

        List<String> outcomes = concurrently(List.of(
                () -> service.update(trader, triggered.get(0), editRequest(5)),
                () -> service.update(trader, triggered.get(1), editRequest(6))));

        assertThat(outcomes).containsExactlyInAnyOrder("DONE", "PLAN_LIMIT_REACHED");
        assertThat(alertsOf(trader, "ACTIVE")).isEqualTo(FREE_ALERTS);
    }

    // ------------------------------------------------------------------------------------------
    // Security
    // ------------------------------------------------------------------------------------------

    @Test
    void UC13_withoutASession_everyEndpointAnswers401() throws Exception {
        String id = UUID.randomUUID().toString();
        for (MockHttpServletRequestBuilder request : List.of(
                get(ALERTS),
                post(ALERTS),
                put(ALERTS + "/" + id),
                delete(ALERTS + "/" + id),
                post(ALERTS + "/" + id + "/pause"),
                post(ALERTS + "/" + id + "/resume"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void UC13_anAdministrator_isNotATrader() throws Exception {
        UUID admin = account(UserRole.ADMIN);
        mvc.perform(get(ALERTS).header(HttpHeaders.AUTHORIZATION, bearer(admin, UserRole.ADMIN)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private List<String> concurrently(List<Callable<?>> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (Callable<?> action : actions) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        action.call();
                        return "DONE";
                    } catch (BusinessException e) {
                        return e.errorCode().code();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static CreateAlertRequest priceRequest(UUID pair, int target) {
        return new CreateAlertRequest(
                pair,
                MarketType.SPOT,
                AlertType.PRICE,
                null,
                null,
                ConditionOperator.CROSS_ABOVE,
                BigDecimal.valueOf(target),
                TriggerMode.ONCE,
                null,
                null,
                null,
                null);
    }

    private static UpdateAlertRequest editRequest(int target) {
        return new UpdateAlertRequest(
                MarketType.SPOT,
                AlertType.PRICE,
                null,
                null,
                ConditionOperator.CROSS_ABOVE,
                BigDecimal.valueOf(target),
                TriggerMode.ONCE,
                null,
                null,
                null,
                null);
    }

    private static String price(UUID pair, String market, String target) {
        return json(rule(pair, market, "PRICE", null, null, "CROSS_ABOVE", target));
    }

    private static String indicator(
            UUID pair, String market, String indicator, String timeframe, String condition, String threshold) {
        return json(rule(pair, market, "INDICATOR", indicator, timeframe, condition, threshold));
    }

    private static String rule(UUID pair, String mode, Integer cooldown, Instant expiresAt) {
        Map<String, Object> body = rule(pair, "SPOT", "PRICE", null, null, "CROSS_ABOVE", "1");
        body.put("triggerMode", mode);
        body.put("cooldownMinutes", cooldown);
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return json(body);
    }

    private static Map<String, Object> rule(
            UUID pair,
            String market,
            String type,
            String indicator,
            String timeframe,
            String condition,
            String threshold) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pairId", pair);
        body.put("market", market);
        body.put("type", type);
        body.put("indicator", indicator);
        body.put("timeframe", timeframe);
        body.put("condition", condition);
        body.put("threshold", threshold == null ? null : new BigDecimal(threshold));
        body.put("triggerMode", "ONCE");
        return body;
    }

    private static String json(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            body.put((String) keyValues[i], keyValues[i + 1]);
        }
        return json(body);
    }

    private static String json(Map<String, Object> body) {
        return body.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> "\"" + entry.getKey() + "\": " + literal(entry.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static String literal(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + value + "\"";
    }

    private String watch(UUID trader, UUID pair) throws Exception {
        return id(as(trader, post(WATCHLIST), "{\"pairId\": \"" + pair + "\"}").andExpect(status().isCreated()));
    }

    private void setStatus(String alertId, String alertStatus) {
        sql.sql("update alert set alert_status = ? where alert_id = ?")
                .params(alertStatus, UUID.fromString(alertId))
                .update();
    }

    private String statusOf(String alertId) {
        return sql.sql("select alert_status from alert where alert_id = ?")
                .param(UUID.fromString(alertId))
                .query(String.class)
                .single();
    }

    private long alertsOf(UUID trader, String alertStatus) {
        return alertStatus == null
                ? sql.sql("select count(*) from alert where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single()
                : sql.sql("select count(*) from alert where user_id = ? and alert_status = ?")
                        .params(trader, alertStatus)
                        .query(Long.class)
                        .single();
    }

    private long watchRows(UUID trader) {
        return sql.sql("select count(*) from watchlist where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private ResultActions as(UUID trader, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader, UserRole.TRADER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(body(created), "$.id");
    }

    private UUID trader() {
        return account(UserRole.TRADER);
    }

    private UUID proTrader() {
        UUID trader = trader();
        subscribeToPro(trader);
        return trader;
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t054.invalid", role.name(), now(), now()).update();
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
                        "T054-" + order,
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
