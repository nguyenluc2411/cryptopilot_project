package com.cryptopilot.watchlist.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.cryptopilot.watchlist.AlertTestData;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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
 * Pause, resume and edit of an alert the Trader has deleted: the alert no longer exists, so each action answers 404
 * MSG41 like any alert the caller cannot see, and none of them brings the alert back or touches the watchlist row.
 *
 * <p>Rule: UC-14; SRS 3.4.3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DeletedAlertApiTest {

    private static final String ALERTS = "/api/v1/alerts";

    private static final String RULE = """
            {"pairId": "%s", "market": "SPOT", "type": "PRICE", "condition": "CROSS_ABOVE", "threshold": %s,
             "triggerMode": "ONCE"}""";

    private static final String EDIT = """
            {"market": "SPOT", "type": "PRICE", "condition": "CROSS_ABOVE", "threshold": 2, "triggerMode": "ONCE"}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private AlertTestData data;
    private UUID trader;
    private String alert;

    @BeforeEach
    void aDeletedAlert() throws Exception {
        data = new AlertTestData(sql);
        trader = data.trader();
        UUID pair = data.pair("DA" + System.nanoTime() % 1_000_000_000L + "USDT");
        alert = JsonPath.read(
                as(post(ALERTS), RULE.formatted(pair, "1"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        as(delete(ALERTS + "/" + alert), null).andExpect(status().isNoContent());
    }

    @AfterEach
    void removeRows() {
        data.clear();
    }

    @Test
    void UC14_pausingADeletedAlert_answers404Msg41_andChangesNothing() throws Exception {
        as(post(ALERTS + "/" + alert + "/pause"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));

        assertStillDeleted();
    }

    @Test
    void UC14_resumingADeletedAlert_answers404Msg41_andChangesNothing() throws Exception {
        as(post(ALERTS + "/" + alert + "/resume"), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));

        assertStillDeleted();
    }

    @Test
    void UC14_editingADeletedAlert_answers404Msg41_andChangesNothing() throws Exception {
        as(put(ALERTS + "/" + alert), EDIT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageCode").value("MSG41"));

        assertStillDeleted();
    }

    /** No alert row of the Trader, none listed, and the watched pair stays watched. */
    private void assertStillDeleted() throws Exception {
        assertThat(count("select count(*) from alert where user_id = ?")).isZero();
        assertThat(count("select count(*) from watchlist where user_id = ?")).isOne();
        as(get(ALERTS), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    private long count(String query) {
        return sql.sql(query).param(trader).query(Long.class).single();
    }

    private ResultActions as(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private String bearer() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(trader.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
