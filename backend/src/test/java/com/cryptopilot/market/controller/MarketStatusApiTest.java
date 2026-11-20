package com.cryptopilot.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.support.MarketIncidents;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * K8 (D-159): the markets' data health, public on {@code GET /api/v1/market/status}, and the administration console's
 * maintenance switch and incidents: maintenance closes a market with the administrator's message and is audited as a
 * system setting; ending it reopens the market, which the status names for a while; only an administrator may switch
 * it.
 *
 * <p>The health monitor is off here: ending maintenance reopens the market at once.
 *
 * <p>Rule: NSF-03; BR-57; D-50; D-159.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MarketStatusApiTest {

    private static final String STATUS = "/api/v1/market/status";
    private static final String ADMIN = "/api/v1/admin/market";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MarketHealthService health;

    private final List<UUID> accounts = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void liveAndClean() {
        health.endMaintenance(MarketType.SPOT);
        health.endMaintenance(MarketType.FUTURES);
        MarketIncidents.removeAll(sql);
        for (UUID account : accounts) {
            sql.sql("delete from audit_log where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        accounts.clear();
    }

    @Test
    void D159_theStatus_isPublic_andBothMarketsAreLive() throws Exception {
        mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markets.length()").value(2))
                .andExpect(jsonPath("$.markets[0].market").value("SPOT"))
                .andExpect(jsonPath("$.markets[0].state").value("LIVE"))
                .andExpect(jsonPath("$.markets[0].tradingOpen").value(true))
                .andExpect(jsonPath("$.markets[1].market").value("FUTURES"))
                .andExpect(jsonPath("$.markets[1].tradingOpen").value(true))
                .andExpect(jsonPath("$.markets[1].incidentId").value(nullValue()));
    }

    /**
     * Maintenance closes the market with the message, the status says so to anyone, and the change is audited; ending
     * it reopens the market, the status names when, and the incident is listed.
     */
    @Test
    void D159_maintenance_closesAMarket_isAudited_andEndingItReopensIt() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "FUTURES", "on": true, "message": "Bảo trì hệ thống đến 22:00"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markets[1].state").value("MAINTENANCE"))
                .andExpect(jsonPath("$.markets[1].tradingOpen").value(false))
                .andExpect(jsonPath("$.markets[1].reason").value("MAINTENANCE"))
                .andExpect(jsonPath("$.markets[1].message").value("Bảo trì hệ thống đến 22:00"))
                .andExpect(jsonPath("$.markets[1].since").isNotEmpty())
                .andExpect(jsonPath("$.markets[1].incidentId").isNotEmpty())
                .andExpect(jsonPath("$.markets[0].tradingOpen").value(true));
        mvc.perform(get(STATUS)).andExpect(jsonPath("$.markets[1].state").value("MAINTENANCE"));
        assertThat(audited(admin, "true")).isOne();

        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "FUTURES", "on": false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markets[1].state").value("LIVE"))
                .andExpect(jsonPath("$.markets[1].tradingOpen").value(true))
                .andExpect(jsonPath("$.markets[1].resumedAt").isNotEmpty())
                .andExpect(jsonPath("$.markets[1].pausedAt").isNotEmpty())
                .andExpect(jsonPath("$.markets[1].incidentId").isNotEmpty());
        assertThat(audited(admin, "false")).isOne();

        as(admin, get(ADMIN + "/incidents").param("market", "FUTURES"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("MAINTENANCE"))
                .andExpect(jsonPath("$[0].createdBy").value(admin.toString()))
                .andExpect(jsonPath("$[0].resumedAt").isNotEmpty());
        as(admin, get(ADMIN + "/incidents").param("market", "SPOT"), null)
                .andExpect(jsonPath("$.length()").value(0));
        as(admin, get(ADMIN + "/incidents").param("market", "SPOT").param("limit", "0"), null)
                .andExpect(status().isBadRequest());
    }

    /** Switching maintenance twice the same way records one change. */
    @Test
    void D159_maintenanceAlreadyOn_isNotAuditedAgain() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "SPOT", "on": true}""").andExpect(status().isOk());
        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "SPOT", "on": true, "message": "  "}""").andExpect(status().isOk());

        assertThat(audited(admin, "true")).isOne();
    }

    /** A maintenance write from a version the incident has moved past is a conflict (409), not a server error. */
    @Test
    void D159_aStaleMaintenanceWrite_answers409() throws Exception {
        UUID admin = account(UserRole.ADMIN);
        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "SPOT", "on": true}""").andExpect(status().isOk());
        sql.sql("update market_incident set version = version + 1").update();

        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "SPOT", "on": true, "message": "deploy"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));

        assertThat(sql.sql("select count(*) from market_incident where resumed_at is null and message is null")
                        .query(Long.class)
                        .single())
                .isOne();
        // Back to the version the service holds, so the clean-up can end the maintenance.
        sql.sql("update market_incident set version = version - 1").update();
    }

    @Test
    void D159_onlyAnAdministrator_switchesMaintenance() throws Exception {
        UUID trader = account(UserRole.TRADER);

        as(trader, post(ADMIN + "/maintenance"), """
                        {"market": "SPOT", "on": true}""").andExpect(status().isForbidden());
        mvc.perform(post(ADMIN + "/maintenance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"market\": \"SPOT\", \"on\": true}"))
                .andExpect(status().isUnauthorized());
        as(account(UserRole.ADMIN), post(ADMIN + "/maintenance"), "{\"on\": true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.market").value("MSG01"));

        mvc.perform(get(STATUS)).andExpect(jsonPath("$.markets[0].state").value("LIVE"));
    }

    /**
     * D-159: only an administrator reopens a market by hand, and only a recovering one: a live market, or one whose data
     * is lost, is refused, and nothing is audited.
     */
    @Test
    void D159_reopeningByHand_isForAdministrators_andOnlyForARecoveringMarket() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        as(account(UserRole.TRADER), post(ADMIN + "/resume"), """
                        {"market": "FUTURES"}""").andExpect(status().isForbidden());
        as(admin, post(ADMIN + "/resume"), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.market").value("MSG01"));
        as(admin, post(ADMIN + "/resume"), """
                        {"market": "FUTURES"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "FUTURES", "on": true}""").andExpect(status().isOk());
        as(admin, post(ADMIN + "/resume"), """
                        {"market": "FUTURES"}""").andExpect(status().isConflict());

        assertThat(sql.sql("""
                        select count(*) from audit_log
                         where user_id = ? and new_value ->> 'key' like 'market.resume.%'""").param(admin).query(Long.class).single()).isZero();
    }

    /**
     * Maintenance may say when it is expected to end, which the banner shows; a time already past is refused. A live
     * market has no retry time and no recovery progress.
     */
    @Test
    void D159_maintenance_mayNameItsExpectedEnd_inTheFuture() throws Exception {
        UUID admin = account(UserRole.ADMIN);
        Instant end = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);

        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "SPOT", "on": true, "expectedEndAt": "%s"}""".formatted(Instant.now().minusSeconds(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expectedEndAt").value("MSG01"));
        as(admin, post(ADMIN + "/maintenance"), """
                        {"market": "SPOT", "on": true, "expectedEndAt": "%s"}""".formatted(end)).andExpect(status().isOk());

        mvc.perform(get(STATUS))
                .andExpect(jsonPath("$.markets[?(@.market == 'SPOT')].expectedEndAt")
                        .value(end.toString()))
                .andExpect(
                        jsonPath("$.markets[?(@.market == 'FUTURES')].retryAt").value(contains((Object) null)))
                .andExpect(
                        jsonPath("$.markets[?(@.market == 'FUTURES')].recovery").value(contains((Object) null)));
        mvc.perform(get(ADMIN + "/incidents")
                        .param("market", "SPOT")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin, UserRole.ADMIN)))
                .andExpect(jsonPath("$[0].expectedEndAt").value(end.toString()));
    }

    /**
     * The fallback prices are public, as the market data is; a live market shows the exchange's own, so none here, and
     * no candles. A candle limit outside 1 to 300 is refused.
     */
    @Test
    void D159_theFallbackPrices_arePublic_andNoneWhileLive() throws Exception {
        mvc.perform(get("/api/v1/market/fallback").param("market", "FUTURES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("BINANCE"))
                .andExpect(jsonPath("$.prices").isEmpty());
        mvc.perform(get("/api/v1/market/fallback/candles")
                        .param("market", "SPOT")
                        .param("symbol", "BTCUSDT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/market/fallback/candles")
                        .param("market", "SPOT")
                        .param("symbol", "BTCUSDT")
                        .param("limit", "301"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(STATUS))
                .andExpect(jsonPath("$.markets[0].displaySource").value("BINANCE"))
                .andExpect(jsonPath("$.markets[0].dataLost").value(false));
    }

    /** E5: an open market names the pairs whose data is lost; none here. */
    @Test
    void D159_anOpenMarket_namesItsLostPairs() throws Exception {
        mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markets[0].pausedPairs").isArray())
                .andExpect(jsonPath("$.markets[0].pausedPairs").isEmpty());
    }

    private long audited(UUID admin, String on) {
        return sql.sql("""
                        select count(*) from audit_log
                         where user_id = ? and action_code = 'CONFIGURATION_CHANGED' and entity_type = 'SYSTEM_SETTING'
                           and new_value ->> 'key' like 'market.maintenance.%' and new_value ->> 'on' = ?""").params(admin, on).query(Long.class).single();
    }

    private ResultActions as(UUID account, MockHttpServletRequestBuilder request, String body) throws Exception {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(account)
                .query(String.class)
                .single();
        request.header(HttpHeaders.AUTHORIZATION, bearer(account, UserRole.valueOf(role)));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@status.invalid", role.name(), now, now).update();
        accounts.add(id);
        return id;
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
}
