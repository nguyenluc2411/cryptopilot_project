package com.cryptopilot.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * RF-07 and RF-12 over HTTP, with the test tools on as in the dev and demo profiles: only an administrator may use
 * them, a request is validated, the scenarios in the repository are listed, and a cut is audited. The streams are off
 * here, so no pair is streamed: a replay is refused as a conflict and a cut cuts nothing. Without the flag the paths
 * do not exist, which {@link MarketStatusApiTest}'s context (flag off) shows.
 *
 * <p>Rule: RF-07, RF-12; BR-57.
 */
@SpringBootTest(properties = "cryptopilot.market.test-tools.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MarketTestToolApiTest {

    private static final String ADMIN = "/api/v1/admin/market";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void clean() {
        for (UUID account : accounts) {
            sql.sql("delete from audit_log where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        accounts.clear();
    }

    /** The scenarios of the demonstrations are in the repository, by name, with their paths' ends. */
    @Test
    void RF07_theRepositorysScenarios_areListed() throws Exception {
        as(account(UserRole.ADMIN), get(ADMIN + "/replay/scenarios"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name")
                        .value(org.hamcrest.Matchers.contains(
                                "btc_hold_60000", "btc_liq", "btc_near_liq", "eth_alert", "eth_hold_3010")))
                .andExpect(jsonPath("$[1].market").value("FUTURES"))
                .andExpect(jsonPath("$[1].symbol").value("BTCUSDT"))
                .andExpect(jsonPath("$[1].lengthSeconds").value(60))
                .andExpect(jsonPath("$[1].firstPrice").value(60000.0))
                .andExpect(jsonPath("$[1].lastPrice").value(59000.0));
        as(account(UserRole.ADMIN), get(ADMIN + "/replay"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    /** A scenario not in the repository is not found; one whose pair is not streamed is a conflict. */
    @Test
    void RF07_anUnknownScenario_isNotFound_andAPairNotStreamed_isAConflict() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        as(admin, post(ADMIN + "/replay"), "{\"scenario\": \"nope\"}").andExpect(status().isNotFound());
        as(admin, post(ADMIN + "/replay"), "{\"scenario\": \"btc_liq\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
        as(admin, post(ADMIN + "/replay/stop"), "{\"market\": \"FUTURES\", \"symbol\": \"BTCUSDT\"}")
                .andExpect(status().isConflict());
        as(admin, post(ADMIN + "/replay"), "{\"scenario\": \" \"}").andExpect(status().isBadRequest());
        as(admin, post(ADMIN + "/replay/stop"), "{\"symbol\": \"BTCUSDT\"}").andExpect(status().isBadRequest());
        assertThat(audited(admin, "market.replay.%")).isZero();
    }

    /** A cut is validated, answers how many connections it cut and when they reconnect, and is audited. */
    @Test
    void RF12_aCut_isValidated_answered_andAudited() throws Exception {
        UUID admin = account(UserRole.ADMIN);

        as(admin, post(ADMIN + "/fault/stream-cut"), "{\"market\": \"FUTURES\", \"outageSeconds\": 45}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("FUTURES"))
                .andExpect(jsonPath("$.connectionsCut").value(0))
                .andExpect(jsonPath("$.cutAt").isNotEmpty())
                .andExpect(jsonPath("$.reconnectFrom").isNotEmpty());
        assertThat(audited(admin, "market.fault.streamCut.FUTURES")).isOne();

        for (String body : List.of(
                "{\"market\": \"FUTURES\", \"outageSeconds\": -1}",
                "{\"market\": \"FUTURES\", \"outageSeconds\": 301}",
                "{\"market\": \"FUTURES\"}",
                "{\"outageSeconds\": 5}")) {
            as(admin, post(ADMIN + "/fault/stream-cut"), body).andExpect(status().isBadRequest());
        }
        assertThat(audited(admin, "market.fault.%")).isOne();
    }

    /** A Trader may use none of the tools. */
    @Test
    void RF07_RF12_aTrader_isForbidden() throws Exception {
        UUID trader = account(UserRole.TRADER);

        as(trader, post(ADMIN + "/fault/stream-cut"), "{\"market\": \"SPOT\", \"outageSeconds\": 5}")
                .andExpect(status().isForbidden());
        as(trader, post(ADMIN + "/replay"), "{\"scenario\": \"btc_liq\"}").andExpect(status().isForbidden());
        as(trader, get(ADMIN + "/replay/scenarios"), null).andExpect(status().isForbidden());
    }

    private long audited(UUID admin, String key) {
        return sql.sql("""
                        select count(*) from audit_log
                         where user_id = ? and action_code = 'CONFIGURATION_CHANGED' and entity_type = 'SYSTEM_SETTING'
                           and new_value ->> 'key' like ?""").params(admin, key).query(Long.class).single();
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
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@tools.invalid", role.name(), now, now).update();
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
