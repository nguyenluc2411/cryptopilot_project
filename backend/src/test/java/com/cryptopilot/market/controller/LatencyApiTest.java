package com.cryptopilot.market.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The client latency samples and the latency report end to end (TR-36): a Trader's client reports, an administrator
 * reads the percentiles, and neither reaches the other's side.
 *
 * <p>Rule: TR-36; TR-34.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class LatencyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void TR36_aTradersSamples_areRecorded_andTheAdministratorReadsTheirPercentiles() throws Exception {
        UUID trader = account("TRADER");
        as(
                        trader,
                        post("/api/v1/analysis/latency-samples")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"samples": [{"kind": "TICKER", "millis": 120},
                                                     {"kind": "TICKER", "millis": 180},
                                                     {"kind": "ORDER", "millis": 400}]}"""))
                .andExpect(status().isNoContent());

        as(account("ADMIN"), get("/api/v1/admin/latency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.subscribedTopics").isNumber())
                .andExpect(
                        jsonPath("$.series[?(@.name == 'cryptopilot.client.latency' && @.tags.kind == 'ticker')].count")
                                .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.series[?(@.tags.kind == 'order')].maxMs")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(400.0))));
    }

    @Test
    void TR36_samplesOutsideTheirRange_areRefused_andEachSideIsItsRolesOnly() throws Exception {
        UUID trader = account("TRADER");
        as(
                        trader,
                        post("/api/v1/analysis/latency-samples")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"samples\": [{\"kind\": \"TICKER\", \"millis\": 60001}]}"))
                .andExpect(status().isBadRequest());
        as(
                        trader,
                        post("/api/v1/analysis/latency-samples")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"samples\": []}"))
                .andExpect(status().isBadRequest());
        as(trader, get("/api/v1/admin/latency")).andExpect(status().isForbidden());
        as(
                        account("ADMIN"),
                        post("/api/v1/analysis/latency-samples")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"samples\": [{\"kind\": \"TICKER\", \"millis\": 1}]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/latency")).andExpect(status().isUnauthorized());
    }

    private UUID account(String role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@tr36.invalid", role, now, now).update();
        return id;
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(user)
                .query(String.class)
                .single();
        Instant issued = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(user.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        String bearer = "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
