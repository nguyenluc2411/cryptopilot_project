package com.cryptopilot.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The package page of the administration console end to end against PostgreSQL (SCR-39, UC-42): the list with the
 * limits of each tier, creating, editing, deactivating and deleting a package, and saving a tier's limits to all of
 * its packages. Each test rolls back.
 *
 * <p>Invariants: (1) the FREE package is the only one of its tier, never sold, never inactive, never deleted; (2) a
 * package with orders is never deleted; (3) the packages of one tier always carry the same limits; (4) codes are
 * unique; (5) every change writes one audit row per package changed and none when nothing changed; (6) only an
 * administrator reaches the page.
 *
 * <p>Rule: SRS 3.11.5; BR-57, BR-62, BR-63; UC-42.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class AdminPackageApiTest {

    private static final String PACKAGES = "/api/v1/admin/packages";

    private static final UUID FREE = UUID.fromString("019b76da-a800-7002-8000-000000000001");
    private static final UUID PRO_MONTHLY = UUID.fromString("019b76da-a800-7002-8000-000000000002");
    private static final UUID PRO_YEARLY = UUID.fromString("019b76da-a800-7002-8000-000000000003");

    private static final String PRO_LIMITS = """
            {"futuresAnalysis": true, "scoreComponents": true, "activePlanMax": null, "watchlistMax": 80,
             "activeAlertMax": 25, "indicatorAlert": true, "externalAlertChannels": true,
             "advancedPerformance": true, "newsAiInsight": true, "aiDailyQuota": 40,
             "aiPerformanceContext": false, "videoPost": true}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntitlementApi entitlements;

    private UUID admin;
    private UUID trader;

    @BeforeEach
    void setUp() {
        admin = account(UserRole.ADMIN);
        trader = account(UserRole.TRADER);
    }

    @Test
    void UC42_thePackagePage_isForAdministratorsOnly() throws Exception {
        as(trader, get(PACKAGES)).andExpect(status().isForbidden());
        as(trader, post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(newPackage("PRO_X")))
                .andExpect(status().isForbidden());
        as(trader, delete(PACKAGES + "/" + PRO_YEARLY)).andExpect(status().isForbidden());
        mvc.perform(get(PACKAGES)).andExpect(status().isUnauthorized());
    }

    @Test
    void UC42_theList_showsEveryPackage_lowestTierFirst_withItsLimits() throws Exception {
        sql.sql("update subscription_package set is_active = false where package_id = ?")
                .param(PRO_YEARLY)
                .update();

        as(admin, get(PACKAGES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("FREE"))
                .andExpect(jsonPath("$[0].tierRank").value(0))
                .andExpect(jsonPath("$[0].durationDays").doesNotExist())
                .andExpect(jsonPath("$[0].purchasable").value(false))
                .andExpect(jsonPath("$[0].limits.watchlistMax").isNumber())
                .andExpect(jsonPath("$[1].code").value("PRO_MONTHLY"))
                .andExpect(jsonPath("$[2].code").value("PRO_YEARLY"))
                .andExpect(jsonPath("$[2].active").value(false))
                .andExpect(jsonPath("$[?(@.code == 'PREMIUM_MONTHLY')].tier").value("PREMIUM"));
        as(admin, get(PACKAGES + "/" + PRO_MONTHLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value("99000"))
                .andExpect(jsonPath("$.currency").value("VND"));
        as(admin, get(PACKAGES + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void UC42_aNewPaidPackage_takesTheLimitsOfItsTier_andIsAudited() throws Exception {
        String body = as(
                        admin,
                        post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(newPackage("PRO_Q")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PRO_Q"))
                .andExpect(jsonPath("$.tier").value("PRO"))
                .andExpect(jsonPath("$.tierRank").value(1))
                .andExpect(jsonPath("$.price").value("249000"))
                .andExpect(jsonPath("$.durationDays").value(90))
                .andExpect(jsonPath("$.purchasable").value(false))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.hasOrders").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String monthly = as(admin, get(PACKAGES + "/" + PRO_MONTHLY))
                .andReturn()
                .getResponse()
                .getContentAsString();

        java.util.Map<String, Object> limits = JsonPath.read(body, "$.limits");
        java.util.Map<String, Object> monthlyLimits = JsonPath.read(monthly, "$.limits");
        assertThat(limits).isEqualTo(monthlyLimits);
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(audits("CONFIGURATION_CREATED", id)).isEqualTo(1);
    }

    @Test
    void UC42_aNewPackage_refusesATakenCode_tierFree_andBadFields() throws Exception {
        as(admin, post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(newPackage("PRO_MONTHLY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_CODE_TAKEN"));
        as(
                        admin,
                        post(PACKAGES)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(newPackage("FREE_2").replace("\"PRO\"", "\"FREE\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREE_PACKAGE_FIXED"));
        as(
                        admin,
                        post(PACKAGES)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(newPackage("PRO_Z").replace("249000", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG15"));
        as(
                        admin,
                        post(PACKAGES)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(newPackage("PRO_Z").replace("\"durationDays\": 90", "\"durationDays\": 367")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.durationDays").value("MSG15"));
        as(admin, post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(newPackage("pro lower")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").value("MSG01"));
    }

    @Test
    void UC42_editingAPaidPackage_changesItsOffer_andAuditsOnlyWhatChanged() throws Exception {
        as(
                        admin,
                        put(PACKAGES + "/" + PRO_MONTHLY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name": "Pro 1 tháng", "price": 109000, "durationDays": 31,
                                         "purchasable": true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pro 1 tháng"))
                .andExpect(jsonPath("$.price").value("109000"))
                .andExpect(jsonPath("$.durationDays").value(31));
        assertThat(audits("CONFIGURATION_CHANGED", PRO_MONTHLY)).isEqualTo(1);
        String newValue = sql.sql("""
                        select new_value::text from audit_log where entity_id = ? and action_code = ?""")
                .params(PRO_MONTHLY, "CONFIGURATION_CHANGED")
                .query(String.class)
                .single();
        assertThat(newValue).contains("109000").contains("\"code\"").doesNotContain("purchasable");

        as(
                        admin,
                        put(PACKAGES + "/" + PRO_MONTHLY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name": "Pro 1 tháng", "price": 109000, "durationDays": 31,
                                         "purchasable": true}"""))
                .andExpect(status().isOk());
        assertThat(audits("CONFIGURATION_CHANGED", PRO_MONTHLY))
                .as("a save that changes nothing writes no audit row")
                .isEqualTo(1);

        as(
                        admin,
                        put(PACKAGES + "/" + PRO_MONTHLY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Pro\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").value("MSG01"))
                .andExpect(jsonPath("$.errors.durationDays").value("MSG01"))
                .andExpect(jsonPath("$.errors.purchasable").value("MSG01"));
    }

    @Test
    void UC42_theFreePackage_takesANewNameOnly() throws Exception {
        as(
                        admin,
                        put(PACKAGES + "/" + FREE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Miễn phí\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Miễn phí"))
                .andExpect(jsonPath("$.price").value("0"));
        as(
                        admin,
                        put(PACKAGES + "/" + FREE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"Free\", \"price\": 1000, \"durationDays\": 30, "
                                        + "\"purchasable\": true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREE_PACKAGE_FIXED"));
        as(
                        admin,
                        patch(PACKAGES + "/" + FREE + "/active")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\": false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREE_PACKAGE_FIXED"));
        as(admin, delete(PACKAGES + "/" + FREE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREE_PACKAGE_FIXED"));
    }

    @Test
    void UC42_aPackage_isTakenOffAndPutBackOnOffer_andAnInactiveOneIsNotListedToTraders() throws Exception {
        as(
                        admin,
                        patch(PACKAGES + "/" + PRO_YEARLY + "/active")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        as(trader, get("/api/v1/billing/packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'PRO_YEARLY')]").isEmpty());

        as(
                        admin,
                        patch(PACKAGES + "/" + PRO_YEARLY + "/active")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\": true}"))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(audits("CONFIGURATION_CHANGED", PRO_YEARLY)).isEqualTo(2);
    }

    @Test
    void UC42_aPackageWithOrders_isNotDeleted_andOneWithout_is() throws Exception {
        order(trader, PRO_YEARLY);
        as(admin, delete(PACKAGES + "/" + PRO_YEARLY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_IN_USE"));
        as(admin, get(PACKAGES + "/" + PRO_YEARLY))
                .andExpect(jsonPath("$.hasOrders").value(true));

        String body = as(
                        admin,
                        post(PACKAGES).contentType(MediaType.APPLICATION_JSON).content(newPackage("PRO_DEL")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        as(admin, delete(PACKAGES + "/" + id)).andExpect(status().isNoContent());
        as(admin, get(PACKAGES + "/" + id)).andExpect(status().isNotFound());
        assertThat(audits("CONFIGURATION_DELETED", id)).isEqualTo(1);
        as(admin, delete(PACKAGES + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void BR62_theLimitsOfATier_areSavedToEveryPackageOfIt_andApplyToTradersAtOnce() throws Exception {
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, subscription_status, start_at,
                                                       end_at, created_at, updated_at)
                        values (?, ?, 'ACTIVE', ?, ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order(trader, PRO_MONTHLY),
                        Timestamp.from(Instant.now().minus(Duration.ofDays(1))),
                        Timestamp.from(Instant.now().plus(Duration.ofDays(29))),
                        Timestamp.from(Instant.now()),
                        Timestamp.from(Instant.now()))
                .update();

        as(
                        admin,
                        put(PACKAGES + "/tiers/PRO/limits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PRO_LIMITS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].limits.watchlistMax").value(80))
                .andExpect(jsonPath("$[1].limits.watchlistMax").value(80))
                .andExpect(jsonPath("$[1].limits.aiDailyQuota").value(40))
                .andExpect(jsonPath("$[1].limits.activePlanMax").doesNotExist());
        assertThat(audits("CONFIGURATION_CHANGED", PRO_MONTHLY)).isEqualTo(1);
        assertThat(audits("CONFIGURATION_CHANGED", PRO_YEARLY)).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();
        assertThat(entitlements.getLimit(trader, Feature.WATCHLIST_MAX)).isEqualTo(80);

        as(
                        admin,
                        put(PACKAGES + "/tiers/PRO/limits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PRO_LIMITS))
                .andExpect(status().isOk());
        assertThat(audits("CONFIGURATION_CHANGED", PRO_MONTHLY))
                .as("saving the same limits again writes no audit row")
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "\"watchlistMax\": 80|\"watchlistMax\": -1|watchlistMax|MSG15",
                "\"aiDailyQuota\": 40|\"aiDailyQuota\": null|aiDailyQuota|MSG01",
                "\"videoPost\": true|\"videoPost\": null|videoPost|MSG01"
            })
    void BR62_tierLimitsOutsideTheirRange_areRefused(String cell) throws Exception {
        String[] parts = cell.split("\\|");
        as(
                        admin,
                        put(PACKAGES + "/tiers/PRO/limits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PRO_LIMITS.replace(parts[0], parts[1])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + parts[2]).value(parts[3]));
    }

    @Test
    void BR62_anUnknownTier_isABadRequest() throws Exception {
        as(
                        admin,
                        put(PACKAGES + "/tiers/GOLD/limits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PRO_LIMITS))
                .andExpect(status().isBadRequest());
    }

    private static String newPackage(String code) {
        return """
                {"code": "%s", "name": "Pro Quarterly", "tier": "PRO", "price": 249000, "durationDays": 90,
                 "purchasable": false}""".formatted(code);
    }

    private UUID order(UUID user, UUID packageId) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, paid_at, created_at, expires_at,
                                                        updated_at)
                        select ?, ?, package_id, ?, price_amount, 'VND', 'SEPAY', 'PAID', ?, ?, ?, ?
                          from subscription_package where package_id = ?""")
                .params(
                        id,
                        user,
                        "CPT"
                                + UUID.randomUUID()
                                        .toString()
                                        .replace("-", "")
                                        .substring(0, 15)
                                        .toUpperCase(),
                        at,
                        at,
                        Timestamp.from(Instant.now().plus(Duration.ofMinutes(15))),
                        at,
                        packageId)
                .update();
        return id;
    }

    /** Audit rows are written through JPA; flushed first, since this transaction reads them with plain SQL. */
    private long audits(String action, UUID packageId) {
        entityManager.flush();
        return sql.sql("""
                        select count(*) from audit_log
                         where user_id = ? and action_code = ? and entity_type = 'SUBSCRIPTION_PACKAGE'
                           and entity_id = ?""").params(admin, action, packageId).query(Long.class).single();
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@uc42.invalid", role.name(), at, at).update();
        return id;
    }

    private String bearer(UUID userId) {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
        Instant issued = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
