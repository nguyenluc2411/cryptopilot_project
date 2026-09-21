package com.cryptopilot.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The declared token configuration: the three windows, and the rule that the signing key has no
 * usable default anywhere.
 *
 * <p>Bound in a bare context rather than the application's, so that each case can supply its own
 * values and the binding itself — including the validation — is what is under test. The windows are
 * also asserted against a running application in {@code LoginAndSessionTest}, which is where they are
 * checked to be the values the issued tokens actually carry.
 *
 * <p>Rule: SRS 3.2.3; D-20; TECHNICAL_DESIGN section 5.3.
 */
class TokenPropertiesTest {

    private static final String VALID_SECRET = "a-secret-long-enough-for-hs256!!";

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(BindTokenProperties.class);

    /**
     * The defaults are the SRS values. Asserted as declared values: a test that only measured an
     * issued token's expiry would pass with any number at all written in the configuration.
     */
    @Test
    void SRS323_theDefaultWindows_areTheValuesTheSrsStates() {
        contexts.withPropertyValues("cryptopilot.auth.token.secret=" + VALID_SECRET)
                .run(context -> {
                    TokenProperties properties = context.getBean(TokenProperties.class);
                    assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
                    assertThat(properties.rememberMeRefreshTokenTtl()).isEqualTo(Duration.ofDays(30));
                });
    }

    /** And the application's own configuration file declares exactly those, not the defaults by luck. */
    @Test
    void SRS323_theShippedConfiguration_declaresTheSameThreeWindows() throws IOException {
        String configuration = applicationYaml();

        assertThat(configuration).contains("access-token-ttl: 15m");
        assertThat(configuration).contains("refresh-token-ttl: 7d");
        assertThat(configuration).contains("remember-me-refresh-token-ttl: 30d");
    }

    /** "Remember me" picks the longer window, and nothing else does. */
    @Test
    void SRS323_theRefreshWindow_isThirtyDaysOnlyWhenRemembered() {
        contexts.withPropertyValues("cryptopilot.auth.token.secret=" + VALID_SECRET)
                .run(context -> {
                    TokenProperties properties = context.getBean(TokenProperties.class);
                    assertThat(properties.refreshTokenTtl(true)).isEqualTo(Duration.ofDays(30));
                    assertThat(properties.refreshTokenTtl(false)).isEqualTo(Duration.ofDays(7));
                });
    }

    /**
     * The key has no default in the file that ships. Asserted by reading the resource, because this is
     * a property of the committed configuration and not of any bound object: a placeholder written
     * {@code ${JWT_SECRET:something}} would bind perfectly well and would put a signing key every
     * reader of the repository holds into every deployment that forgot to override it (D-20).
     */
    @Test
    void D20_theSigningKey_isDeclaredWithNoFallback() throws IOException {
        assertThat(applicationYaml())
                .as("a fallback of any kind here is a committed credential")
                .contains("secret: ${JWT_SECRET}")
                .doesNotContain("${JWT_SECRET:");
    }

    /** An environment that supplies nothing fails at start-up rather than starting insecurely. */
    @Test
    void D20_anApplicationWithNoSigningKey_doesNotStart() {
        contexts.run(context -> assertThat(context).hasFailed());
    }

    /** And one that supplies a key too short for HS256 fails just as loudly. */
    @ParameterizedTest(name = "a {0}-character key is refused")
    @ValueSource(ints = {1, 16, 31})
    void D20_aKeyShorterThanTheDigest_isRefused(int length) {
        contexts.withPropertyValues("cryptopilot.auth.token.secret=" + "k".repeat(length))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void D20_aKeyOfExactlyTwoHundredAndFiftySixBits_isAccepted() {
        contexts.withPropertyValues("cryptopilot.auth.token.secret=" + "k".repeat(32))
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** A blank key is not a key, however long the blanks are. */
    @Test
    void D20_aBlankSigningKey_isRefused() {
        contexts.withPropertyValues("cryptopilot.auth.token.secret=" + " ".repeat(40))
                .run(context -> assertThat(context).hasFailed());
    }

    private static String applicationYaml() throws IOException {
        try (InputStream in = TokenPropertiesTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("the shipped configuration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @EnableConfigurationProperties(TokenProperties.class)
    static class BindTokenProperties {}
}
