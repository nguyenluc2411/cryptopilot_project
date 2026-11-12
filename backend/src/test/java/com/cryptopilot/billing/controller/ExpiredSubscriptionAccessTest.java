package com.cryptopilot.billing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.community.InMemoryMediaStorage;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * NFR-04: when a paid subscription has ended, the plan is FREE at once, so the paid features are refused with MSG29:
 * the AI Assistant (BR-50) and video posting (BR-43). The same Trader is let through while the subscription runs.
 *
 * <p>Rule: NFR-04; BR-62, BR-64; UC-53; MSG29.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, ExpiredSubscriptionAccessTest.Storage.class})
@Transactional
class ExpiredSubscriptionAccessTest {

    private static final UUID PREMIUM_MONTHLY = UUID.fromString("019b76da-a800-7002-8000-000000000004");

    @TestConfiguration(proxyBeanMethods = false)
    static class Storage {

        @Bean
        @Primary
        InMemoryMediaStorage inMemoryMediaStorage() {
            return new InMemoryMediaStorage();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void NFR04_afterTheSubscriptionExpired_theAiAssistantAndVideoPosting_areRefusedWithMsg29() throws Exception {
        UUID trader = trader();
        subscription(
                trader, Instant.now().minus(Duration.ofDays(31)), Instant.now().minus(Duration.ofDays(1)), "EXPIRED");

        as(
                        trader,
                        post("/api/v1/ai/conversations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
        as(
                        trader,
                        post("/api/v1/media/upload-signature")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"mediaType\": \"VIDEO\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
    }

    /** The hourly run has not marked it yet: an ACTIVE row past its end already grants nothing. */
    @Test
    void NFR04_anActiveRowPastItsEnd_grantsNothingBeforeTheHourlyRun() throws Exception {
        UUID trader = trader();
        subscription(
                trader, Instant.now().minus(Duration.ofDays(30)), Instant.now().minus(Duration.ofMinutes(1)), "ACTIVE");

        as(
                        trader,
                        post("/api/v1/media/upload-signature")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"mediaType\": \"VIDEO\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageCode").value("MSG29"));
    }

    @Test
    void NFR04_whileTheSubscriptionRuns_videoPostingIsAllowed() throws Exception {
        UUID trader = trader();
        subscription(
                trader, Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(29)), "ACTIVE");

        as(
                        trader,
                        post("/api/v1/media/upload-signature")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"mediaType\": \"VIDEO\"}"))
                .andExpect(status().isOk());
    }

    private void subscription(UUID trader, Instant start, Instant end, String status) {
        UUID order = UUID.randomUUID();
        Timestamp at = Timestamp.from(start);
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at,
                                                        expires_at, updated_at)
                        values (?, ?, ?, ?, 199000, 'VND', 'SEPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        trader,
                        PREMIUM_MONTHLY,
                        "NFR04-" + order,
                        at,
                        at,
                        Timestamp.from(start.plusSeconds(900)),
                        at)
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), order, at, Timestamp.from(end), status, at, at)
                .update();
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@nfr04.invalid", now, now).update();
        return id;
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private String bearer(UUID userId) {
        Instant issued = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, "TRADER")
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
