package com.cryptopilot.watchlist.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.cryptopilot.watchlist.dto.request.AddWatchlistItemRequest;
import com.cryptopilot.watchlist.service.WatchlistService;
import com.jayway.jsonpath.JsonPath;
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
 * The watchlist API end to end: security, validation, ownership, BR-15's uniqueness, the plan limit of BR-62 against
 * the seeded packages and the D-63 lock under concurrent adds.
 *
 * <p>Nothing is rolled back, because the concurrency tests need committed transactions: every test writes its own
 * Traders and pairs and {@link #removeRows()} deletes them.
 *
 * <p>Rule: UC-12; BR-07, BR-15, BR-16, BR-62, BR-64; D-63.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WatchlistApiTest {

    private static final String WATCHLIST = "/api/v1/watchlist";

    /** The PRO package of 30 days that V11 seeded. */
    private static final UUID PRO_PACKAGE = UUID.fromString("019b76da-a800-7002-8000-000000000003");

    /** {@code WATCHLIST_MAX} of the seeded FREE package (D-58). */
    private static final int FREE_LIMIT = 5;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private WatchlistService service;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> pairs = new ArrayList<>();
    private final List<String> symbols = new ArrayList<>();
    private UUID unlistedPair;

    @BeforeEach
    void insertPairs() {
        MarketTestData market = new MarketTestData(sql, Instant.now());
        String run = Long.toString(System.nanoTime() % 1_000_000_000L);
        for (int i = 0; i < 10; i++) {
            String symbol = "WL" + run + (char) ('A' + i) + "USDT";
            pairs.add(market.pair(symbol, true, i % 2 == 0, "TRADING", null, i));
            symbols.add(symbol);
        }
        unlistedPair = market.pair("WL" + run + "XUSDT", false, false, null, null, 0);
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
    // Adding, listing, editing, removing (UC-12, SRS 3.4.1)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC12_aListedPair_isAddedAtTheEndOfTheList() throws Exception {
        UUID trader = trader();
        as(trader, post(WATCHLIST), add(pairs.get(0), "Majors", "breakout watch"))
                .andExpect(status().isCreated());

        as(trader, post(WATCHLIST), add(pairs.get(1), null, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pairId").value(pairs.get(1).toString()))
                .andExpect(jsonPath("$.symbol").value(symbols.get(1)))
                .andExpect(jsonPath("$.spotListed").value(true))
                .andExpect(jsonPath("$.futuresListed").value(false))
                .andExpect(jsonPath("$.sortOrder").value(1))
                .andExpect(jsonPath("$.activeAlerts").value(0))
                .andExpect(jsonPath("$.addedAt").exists());

        as(trader, get(WATCHLIST), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(FREE_LIMIT))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].symbol").value(symbols.get(0)))
                .andExpect(jsonPath("$.items[0].label").value("Majors"))
                .andExpect(jsonPath("$.items[0].note").value("breakout watch"))
                .andExpect(jsonPath("$.items[0].futuresListed").value(true))
                .andExpect(jsonPath("$.items[1].symbol").value(symbols.get(1)));
    }

    @Test
    void UC12_anEmptyWatchlist_isAnEmptyList() throws Exception {
        as(trader(), get(WATCHLIST), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void UC12_aRowIsRelabelledAnnotatedAndMoved_andTheListFollowsTheNewOrder() throws Exception {
        UUID trader = trader();
        String first = id(as(trader, post(WATCHLIST), add(pairs.get(0), "Majors", "note")));
        as(trader, post(WATCHLIST), add(pairs.get(1), null, null)).andExpect(status().isCreated());

        as(trader, patch(WATCHLIST + "/" + first), "{\"label\": \"Alts\", \"sortOrder\": 5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Alts"))
                .andExpect(jsonPath("$.note").value("note"))
                .andExpect(jsonPath("$.sortOrder").value(5));
        as(trader, patch(WATCHLIST + "/" + first), "{\"note\": \"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Alts"))
                .andExpect(jsonPath("$.note").doesNotExist());

        as(trader, get(WATCHLIST), null)
                .andExpect(jsonPath("$.items[0].symbol").value(symbols.get(1)))
                .andExpect(jsonPath("$.items[1].id").value(first));
    }

    @Test
    void UC12_aRowWithoutAlerts_isRemovedWithoutConfirmation() throws Exception {
        UUID trader = trader();
        String row = id(as(trader, post(WATCHLIST), add(pairs.get(0), null, null)));

        as(trader, delete(WATCHLIST + "/" + row), null).andExpect(status().isNoContent());

        assertThat(rowsOf(trader)).isZero();
    }

    @Test
    void BR16_aRowWithAlerts_needsMsg26sConfirmation_andThenGoesWithItsAlerts() throws Exception {
        UUID trader = trader();
        String row = id(as(trader, post(WATCHLIST), add(pairs.get(0), null, null)));
        alert(trader, UUID.fromString(row), "ACTIVE");
        alert(trader, UUID.fromString(row), "PAUSED");

        as(trader, get(WATCHLIST), null)
                .andExpect(jsonPath("$.items[0].activeAlerts").value(1));
        as(trader, patch(WATCHLIST + "/" + row), "{\"label\": \"x\"}")
                .andExpect(jsonPath("$.activeAlerts").value(1));

        as(trader, delete(WATCHLIST + "/" + row), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WATCHLIST_REMOVAL_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.messageCode").value("MSG26"))
                .andExpect(jsonPath("$.messageArgs[0]").value(symbols.get(0)))
                .andExpect(jsonPath("$.messageArgs[1]").value("2"));
        assertThat(rowsOf(trader)).isOne();

        as(trader, delete(WATCHLIST + "/" + row + "?confirm=true"), null).andExpect(status().isNoContent());

        assertThat(rowsOf(trader)).isZero();
        assertThat(sql.sql("select count(*) from alert where user_id = ?")
                        .param(trader)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ------------------------------------------------------------------------------------------
    // Which pairs (BR-07, BR-15)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR15_aPairAlreadyWatched_isRefusedWithMsg45_andStoredOnce() throws Exception {
        UUID trader = trader();
        as(trader, post(WATCHLIST), add(pairs.get(0), null, null)).andExpect(status().isCreated());

        as(trader, post(WATCHLIST), add(pairs.get(0), "again", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WATCHLIST_PAIR_ALREADY_WATCHED"))
                .andExpect(jsonPath("$.messageCode").value("MSG45"))
                .andExpect(jsonPath("$.messageArgs[0]").value(symbols.get(0)));

        assertThat(rowsOf(trader)).isOne();
    }

    @Test
    void BR15_twoTradersMayWatchTheSamePair() throws Exception {
        as(trader(), post(WATCHLIST), add(pairs.get(0), null, null)).andExpect(status().isCreated());
        as(trader(), post(WATCHLIST), add(pairs.get(0), null, null)).andExpect(status().isCreated());
    }

    @Test
    void BR07_anUnknownOrUnlistedPair_isNotFound() throws Exception {
        UUID trader = trader();

        as(trader, post(WATCHLIST), add(UUID.randomUUID(), null, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(trader, post(WATCHLIST), add(unlistedPair, null, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(rowsOf(trader)).isZero();
    }

    @Test
    void BR07_aWatchedPairThatIsNoLongerListed_staysInTheListMarkedSo() throws Exception {
        UUID trader = trader();
        as(trader, post(WATCHLIST), add(pairs.get(0), null, null)).andExpect(status().isCreated());
        new MarketTestData(sql, Instant.now()).enable(pairs.get(0), false, false);

        as(trader, get(WATCHLIST), null)
                .andExpect(jsonPath("$.items[0].symbol").value(symbols.get(0)))
                .andExpect(jsonPath("$.items[0].spotListed").value(false))
                .andExpect(jsonPath("$.items[0].futuresListed").value(false));
    }

    // ------------------------------------------------------------------------------------------
    // Ownership (OWASP API1)
    // ------------------------------------------------------------------------------------------

    @Test
    void UC12_anotherTradersRow_isNeitherListedNorChangedNorRemoved() throws Exception {
        UUID owner = trader();
        UUID other = trader();
        String row = id(as(owner, post(WATCHLIST), add(pairs.get(0), "mine", null)));

        as(other, get(WATCHLIST), null).andExpect(jsonPath("$.items.length()").value(0));
        as(other, patch(WATCHLIST + "/" + row), "{\"label\": \"theirs\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));
        as(other, delete(WATCHLIST + "/" + row + "?confirm=true"), null).andExpect(status().isNotFound());

        as(owner, get(WATCHLIST), null)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].label").value("mine"));
    }

    // ------------------------------------------------------------------------------------------
    // Plan limit (BR-15, BR-62, BR-64)
    // ------------------------------------------------------------------------------------------

    @Test
    void BR62_aFreeTraderAtFivePairs_isRefusedTheSixthWithMsg27() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < FREE_LIMIT; i++) {
            as(trader, post(WATCHLIST), add(pairs.get(i), null, null)).andExpect(status().isCreated());
        }

        as(trader, post(WATCHLIST), add(pairs.get(FREE_LIMIT), null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"))
                .andExpect(jsonPath("$.messageCode").value("MSG27"))
                .andExpect(jsonPath("$.messageArgs[0]").value(String.valueOf(FREE_LIMIT)))
                .andExpect(jsonPath("$.messageArgs[2]").value("FREE"));

        assertThat(rowsOf(trader)).isEqualTo(FREE_LIMIT);
    }

    @Test
    void BR62_aProTrader_addsBeyondTheFreeLimit_andSeesItsOwnLimit() throws Exception {
        UUID trader = trader();
        subscribeToPro(trader);
        for (int i = 0; i <= FREE_LIMIT; i++) {
            as(trader, post(WATCHLIST), add(pairs.get(i), null, null)).andExpect(status().isCreated());
        }

        as(trader, get(WATCHLIST), null)
                .andExpect(jsonPath("$.items.length()").value(FREE_LIMIT + 1))
                .andExpect(jsonPath("$.limit").value(50));
    }

    @Test
    void BR64_rowsAboveALoweredLimit_areKept_butNoneCanBeAdded() throws Exception {
        UUID trader = trader();
        for (int i = 0; i <= FREE_LIMIT; i++) {
            insertRow(trader, pairs.get(i), i);
        }

        as(trader, get(WATCHLIST), null).andExpect(jsonPath("$.items.length()").value(FREE_LIMIT + 1));
        as(trader, post(WATCHLIST), add(pairs.get(FREE_LIMIT + 1), null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageCode").value("MSG27"));
    }

    /** Two adds at {@code max − 1}: the lock lets one count and insert before the other counts (D-63). */
    @RepeatedTest(10)
    void D63_twoConcurrentAddsAtTheLimitMinusOne_letExactlyOneThrough() throws Exception {
        UUID trader = trader();
        for (int i = 0; i < FREE_LIMIT - 1; i++) {
            service.add(trader, new AddWatchlistItemRequest(pairs.get(i), null, null));
        }

        List<String> results = concurrently(trader, pairs.subList(FREE_LIMIT - 1, FREE_LIMIT + 1));

        assertThat(results).containsExactlyInAnyOrder("ADDED", ErrorCode.PLAN_LIMIT_REACHED.code());
        assertThat(rowsOf(trader)).isEqualTo(FREE_LIMIT);
    }

    @Test
    void D63_tenConcurrentAddsFromEmpty_neverExceedTheLimit() throws Exception {
        UUID trader = trader();

        List<String> results = concurrently(trader, pairs);

        assertThat(results).filteredOn("ADDED"::equals).hasSize(FREE_LIMIT);
        assertThat(results)
                .filteredOn(ErrorCode.PLAN_LIMIT_REACHED.code()::equals)
                .hasSize(10 - FREE_LIMIT);
        assertThat(rowsOf(trader)).isEqualTo(FREE_LIMIT);
    }

    @Test
    void BR15_concurrentAddsOfTheSamePair_storeItOnce() throws Exception {
        UUID trader = trader();

        List<String> results = concurrently(trader, List.of(pairs.get(0), pairs.get(0), pairs.get(0)));

        assertThat(results)
                .containsExactlyInAnyOrder(
                        "ADDED",
                        ErrorCode.WATCHLIST_PAIR_ALREADY_WATCHED.code(),
                        ErrorCode.WATCHLIST_PAIR_ALREADY_WATCHED.code());
        assertThat(rowsOf(trader)).isOne();
    }

    // ------------------------------------------------------------------------------------------
    // Security and validation
    // ------------------------------------------------------------------------------------------

    @Test
    void UC12_withoutASession_everyEndpointAnswers401() throws Exception {
        UUID row = UUID.randomUUID();
        mvc.perform(get(WATCHLIST)).andExpect(status().isUnauthorized());
        mvc.perform(post(WATCHLIST).contentType(MediaType.APPLICATION_JSON).content(add(pairs.get(0), null, null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(WATCHLIST + "/" + row)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(WATCHLIST + "/" + row)).andExpect(status().isUnauthorized());
    }

    @Test
    void UC12_anAdministrator_isNotATrader() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        mvc.perform(get(WATCHLIST).header(HttpHeaders.AUTHORIZATION, bearer(admin, UserRole.ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void UC12_invalidFields_areRefusedWithMsg01() throws Exception {
        UUID trader = trader();
        String row = id(as(trader, post(WATCHLIST), add(pairs.get(0), null, null)));

        as(trader, post(WATCHLIST), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.errors.pairId").exists());
        as(trader, post(WATCHLIST), add(pairs.get(1), "x".repeat(101), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.label").exists());
        as(trader, patch(WATCHLIST + "/" + row), "{\"sortOrder\": -1, \"note\": \"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sortOrder").exists())
                .andExpect(jsonPath("$.errors.note").exists());
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private List<String> concurrently(UUID trader, List<UUID> pairIds) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(pairIds.size());
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (UUID pair : pairIds) {
                outcomes.add(pool.submit(adding(trader, pair, start)));
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

    private Callable<String> adding(UUID trader, UUID pair, CountDownLatch start) {
        return () -> {
            start.await();
            try {
                service.add(trader, new AddWatchlistItemRequest(pair, null, null));
                return "ADDED";
            } catch (BusinessException e) {
                return e.errorCode().code();
            }
        };
    }

    private ResultActions as(UUID trader, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader, UserRole.TRADER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String add(UUID pair, String label, String note) {
        return "{\"pairId\": \"" + pair + "\""
                + (label == null ? "" : ", \"label\": \"" + label + "\"")
                + (note == null ? "" : ", \"note\": \"" + note + "\"")
                + "}";
    }

    private long rowsOf(UUID trader) {
        return sql.sql("select count(*) from watchlist where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private void insertRow(UUID trader, UUID pair, int sortOrder) {
        sql.sql("""
                        insert into watchlist (watchlist_id, user_id, pair_id, sort_order, added_at, created_at,
                                               updated_at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), trader, pair, sortOrder, now(), now(), now())
                .update();
    }

    private void alert(UUID trader, UUID watchlistId, String status) {
        sql.sql("""
                        insert into alert (alert_id, user_id, watchlist_id, market_type, alert_type,
                                           condition_operator, threshold_value, trigger_mode, alert_status,
                                           created_at, updated_at)
                        values (?, ?, ?, 'SPOT', 'PRICE', 'CROSS_ABOVE', 100, 'ONCE', ?, ?, ?)""")
                .params(UUID.randomUUID(), trader, watchlistId, status, now(), now())
                .update();
    }

    private UUID trader() {
        return account(UserRole.TRADER);
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t053.invalid", role.name(), now(), now()).update();
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
                        "T053-" + order,
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
