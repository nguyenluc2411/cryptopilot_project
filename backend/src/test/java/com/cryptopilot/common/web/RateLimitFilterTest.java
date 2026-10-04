package com.cryptopilot.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.MutableTestClock;
import com.cryptopilot.support.TestcontainersConfig;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The limits of ADR-014 at the HTTP edge: sign-in per client address, the other public auth calls per client address,
 * authenticated calls per account; over the limit, 429 with {@code Retry-After} and the standard problem body.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3; ADR-014.
 */
@SpringBootTest(
        properties = {
            "cryptopilot.web.rate-limit.enabled=true",
            "cryptopilot.web.rate-limit.login=2",
            "cryptopilot.web.rate-limit.auth=3",
            "cryptopilot.web.rate-limit.api=2"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, RateLimitFilterTest.TestClock.class})
class RateLimitFilterTest {

    /** 15 seconds into a minute. */
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:15Z");

    private static final String LOGIN = "{\"email\": \"nobody@ratelimit.invalid\", \"password\": \"Abcdefg1\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    @AfterEach
    void resetCountersAndClock() {
        clock.set(NOW);
        Set<String> keys = redis.keys(FixedWindowRateLimiter.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void TD53_signInsUnderTheLimit_pass_andTheNextIs429WithRetryAfter() throws Exception {
        login("203.0.113.7").andExpect(status().isUnauthorized());
        login("203.0.113.7").andExpect(status().isUnauthorized());

        login("203.0.113.7")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.messageCode").value("MSG50"))
                .andExpect(jsonPath("$.messageArgs[0]").value("45"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void TD53_theWindowResets() throws Exception {
        login("203.0.113.7");
        login("203.0.113.7");
        login("203.0.113.7").andExpect(status().isTooManyRequests());

        clock.set(Instant.parse("2026-10-04T10:01:00Z"));

        login("203.0.113.7").andExpect(status().isUnauthorized());
    }

    /** The key is the resolved client: another address has its own count, a spoofed forwarded header does not. */
    @Test
    void TD53_theSignInLimit_isPerClientAddress() throws Exception {
        login("203.0.113.7");
        login("203.0.113.7");

        login("203.0.113.8").andExpect(status().isUnauthorized());
        mvc.perform(withPeer(post("/api/v1/auth/login"), "203.0.113.7")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void TD53_theOtherPublicAuthCalls_haveTheirOwnLimit() throws Exception {
        login("203.0.113.7");
        login("203.0.113.7");

        for (int i = 0; i < 3; i++) {
            refresh("203.0.113.7").andExpect(status().isUnauthorized());
        }
        refresh("203.0.113.7").andExpect(status().isTooManyRequests());
    }

    @Test
    void TD53_authenticatedCalls_areLimitedPerAccount() throws Exception {
        String first = bearer(UUID.randomUUID());
        String second = bearer(UUID.randomUUID());

        watchlist(first).andExpect(status().isOk());
        watchlist(first).andExpect(status().isOk());
        watchlist(first).andExpect(status().isTooManyRequests()).andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        watchlist(second).andExpect(status().isOk());
    }

    @Test
    void TD53_publicReadsAndRefusedCalls_areNotCounted() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/watchlist")).andExpect(status().isUnauthorized());
        }
    }

    private ResultActions login(String peer) throws Exception {
        return mvc.perform(withPeer(post("/api/v1/auth/login"), peer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN));
    }

    private ResultActions refresh(String peer) throws Exception {
        return mvc.perform(withPeer(post("/api/v1/auth/refresh"), peer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\": \"not-a-token\"}"));
    }

    private ResultActions watchlist(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/watchlist").header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private static MockHttpServletRequestBuilder withPeer(MockHttpServletRequestBuilder request, String peer) {
        return request.with(servletRequest -> {
            servletRequest.setRemoteAddr(peer);
            return servletRequest;
        });
    }

    private String bearer(UUID userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, "TRADER")
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }

    @TestConfiguration
    static class TestClock {

        @Bean
        MutableTestClock testClock() {
            return new MutableTestClock(NOW);
        }

        @Bean
        @Primary
        Clock clockUnderTest(MutableTestClock testClock) {
            return testClock;
        }
    }
}
