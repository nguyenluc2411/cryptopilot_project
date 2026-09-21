package com.cryptopilot.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
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
 * <h2>401 and 403 now mean different things</h2>
 *
 * <p>Until a bearer token could be presented, every refusal was a 403: there was no way to
 * authenticate, so "not authenticated" and "not allowed" were the same state. With the resource
 * server in the chain they separate, and the assertions below separate with them — no credential is
 * 401 with a {@code WWW-Authenticate} challenge, a credential that is simply not allowed anywhere yet
 * is 403. {@code anyRequest().denyAll()} is unchanged and is what produces the second one.
 *
 * <p>Rule: BR-02; SRS 4.2.4, UC-01, UC-02, UC-03, UC-05; TECHNICAL_DESIGN sections 1.3 and 5.3.
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

    @Autowired
    private JwtEncoder jwtEncoder;

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
     * The endpoints a caller with no session must reach. Reachable means "not refused by the chain":
     * each is driven with an empty body, so a 400 proves the request got as far as validation, which
     * is past the filter chain.
     */
    @ParameterizedTest(name = "{0} is reachable without signing in")
    @ValueSource(
            strings = {
                "/api/v1/auth/register",
                "/api/v1/auth/verify-email",
                "/api/v1/auth/resend-verification",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout"
            })
    void UC01_thePublicAuthEndpoints_areReachableUnauthenticated(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * And they are the only ones named, so the declared list cannot quietly grow.
     *
     * <p>It grew by three here, deliberately and one path at a time: signing in, renewing a session
     * and signing out are each reached by a caller who has no usable access token, and each is written
     * out rather than covered by a wildcard over {@code /api/v1/auth/**} — a wildcard would also open
     * whatever this controller is given next, including the password change that must not be open.
     */
    @Test
    void UC03_thePublicEndpoints_areTheDeclaredSix() {
        assertThat(SecurityConfig.PUBLIC_AUTH_ENDPOINTS)
                .containsExactly(
                        "/api/v1/auth/register",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/resend-verification",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout");
    }

    /**
     * Everything else is closed to a caller with no credential, and says so the way HTTP says it: 401
     * with a challenge naming the scheme. This is one of the two assertions that fail the moment
     * somebody replaces {@code denyAll} with {@code permitAll} while building a later task.
     */
    @ParameterizedTest(name = "{0} challenges an anonymous caller")
    @ValueSource(strings = {"/api/v1/users", "/api/v1/auth/me", "/actuator/env", "/actuator/beans", "/anything"})
    void NSF_anEndpointThatWasNotOpened_challengesAnAnonymousCaller(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")));
    }

    /**
     * And it is the other one: a caller who <em>is</em> authenticated is still refused, because
     * {@code denyAll} is a rule about the endpoint and not about the caller. This is what would start
     * passing as 200 if the default were widened, and it is why the assertion is worth making with a
     * real signed token rather than only anonymously.
     */
    @ParameterizedTest(name = "{0} is denied even to a signed-in caller")
    @ValueSource(strings = {"/api/v1/users", "/actuator/env", "/anything"})
    void NSF_anEndpointThatWasNotOpened_isDeniedEvenWithAValidToken(String path) throws Exception {
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + validAccessToken()))
                .andExpect(status().isForbidden());
    }

    /** A public endpoint is public for the method it was opened for, and not for the others. */
    @Test
    void NSF_thePublicEndpoints_areOpenedForPostOnly() throws Exception {
        mvc.perform(get("/api/v1/auth/register")).andExpect(status().isUnauthorized());
    }

    /** The health probes an orchestrator reads, which are narrower than the whole actuator. */
    @Test
    void NSF_theHealthProbes_areReachableAndTheRestOfTheActuatorIsNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    /**
     * A token the application did not sign is not a credential, however well formed it is.
     *
     * <p>The value below is a real JWT with the right shape and a signature made with another key —
     * which is the only interesting case, because a malformed string would be rejected by parsing and
     * would prove nothing about whether the signature is checked at all.
     */
    @Test
    void NSF_anAccessTokenSignedWithAnotherKey_isNotACredential() throws Exception {
        String forged = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                        "a-different-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256")))
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .issuer("cryptopilot")
                                .subject(UUID.randomUUID().toString())
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(900))
                                .claim("role", "ADMIN")
                                .build()))
                .getTokenValue();

        mvc.perform(get("/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    /** An expired token is refused by the decoder, before any claim on it is read. */
    @Test
    void NSF_anExpiredAccessToken_isNotACredential() throws Exception {
        mvc.perform(get("/anything")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + accessTokenExpiringAt(Instant.now().minusSeconds(1))))
                .andExpect(status().isUnauthorized());
    }

    private String validAccessToken() {
        return accessTokenExpiringAt(Instant.now().plusSeconds(900));
    }

    private String accessTokenExpiringAt(Instant expiresAt) {
        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .issuer("cryptopilot")
                                .subject(UUID.randomUUID().toString())
                                .issuedAt(expiresAt.minusSeconds(900))
                                .expiresAt(expiresAt)
                                .claim("role", "TRADER")
                                .build()))
                .getTokenValue();
    }
}
