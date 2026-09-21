package com.cryptopilot.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the filter chain lets through, and what the encoder is set to.
 *
 * <p>Two kinds of assertion, and both are needed. The behavioural ones drive real requests through
 * the chain and check the status. The declared ones read the constants the configuration is written
 * from — because a behavioural test alone keeps passing when the strength argument is deleted (the
 * encoder silently falls back to bcrypt's default of 10) or when {@code denyAll} is widened to
 * {@code permitAll}, and a rule nobody can break is a rule nobody is testing.
 *
 * <p>Rule: BR-02; SRS 4.2.4, UC-01, UC-02; TECHNICAL_DESIGN sections 1.3 and 5.3.
 *
 * <p>Reference: Provos, N. &amp; Mazières, D. (1999). <i>A Future-Adaptable Password Scheme</i>.
 * USENIX (the cost factor is part of the stored hash).
 * <p>Reference: OWASP Application Security Verification Standard v4, section 2.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * TECHNICAL_DESIGN 1.3 fixes the cost at 12, and bcrypt records it in every hash it writes.
     * Asserting the constant as well as a hash catches the two ways this drifts: changing the bean
     * without the constant, and deleting the argument so the default applies.
     */
    @Test
    void BR02_theEncoder_isBcryptAtTheDeclaredCostOfTwelve() {
        assertThat(SecurityConfig.BCRYPT_STRENGTH).as("TECHNICAL_DESIGN 1.3").isEqualTo(12);
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);
        assertThat(passwordEncoder.encode("Abcdefg1"))
                .as("bcrypt writes its own cost factor into the hash")
                .startsWith("$2a$12$");
    }

    /** The same password twice gives two hashes: the salt is per password, not per application. */
    @Test
    void BR02_twoHashesOfOnePassword_differ() {
        String first = passwordEncoder.encode("Abcdefg1");
        String second = passwordEncoder.encode("Abcdefg1");

        assertThat(first).isNotEqualTo(second);
        assertThat(passwordEncoder.matches("Abcdefg1", first)).isTrue();
        assertThat(passwordEncoder.matches("Abcdefg1", second)).isTrue();
    }

    /**
     * The three endpoints UC-01 and UC-02 need before an account exists. Reachable means "not 401
     * or 403": each is driven with an empty body, so a 400 proves the request got as far as
     * validation, which is past the filter chain.
     */
    @ParameterizedTest(name = "{0} is reachable without signing in")
    @ValueSource(strings = {"/api/v1/auth/register", "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification"})
    void UC01_thePublicAuthEndpoints_areReachableUnauthenticated(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** And they are the only ones named, so the declared list cannot quietly grow. */
    @Test
    void UC01_thePublicEndpoints_areTheDeclaredThree() {
        assertThat(SecurityConfig.PUBLIC_AUTH_ENDPOINTS)
                .containsExactly(
                        "/api/v1/auth/register", "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification");
    }

    /**
     * Everything else is denied by default. This is the assertion that fails the moment somebody
     * replaces {@code denyAll} with {@code permitAll} while building a later task.
     */
    @ParameterizedTest(name = "{0} is denied")
    @ValueSource(strings = {"/api/v1/users", "/api/v1/auth/login", "/actuator/env", "/actuator/beans", "/anything"})
    void NSF_anEndpointThatWasNotOpened_isDenied(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isForbidden());
    }

    /** A public endpoint is public for the method it was opened for, and not for the others. */
    @Test
    void NSF_thePublicEndpoints_areOpenedForPostOnly() throws Exception {
        mvc.perform(get("/api/v1/auth/register")).andExpect(status().isForbidden());
    }

    /** The health probes an orchestrator reads, which are narrower than the whole actuator. */
    @Test
    void NSF_theHealthProbes_areReachableAndTheRestOfTheActuatorIsNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }
}
