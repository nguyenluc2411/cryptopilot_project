package com.cryptopilot.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.user.UserRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The step between a verified token and an authenticated caller, tested without a filter chain.
 *
 * <p>{@code AuthorizationMatrixTest} proves the rules; this proves the thing the rules read. The
 * two are worth separating because a single wrong answer here makes every rule in the matrix wrong
 * in the same direction, and a failure in this class says why in one line instead of forty.
 *
 * <p>Rule: BR-05; SRS 3.1.3, SRS 4.2.4; TECHNICAL_DESIGN section 5.3.
 */
class AccessTokenAuthenticationConverterTest {

    private final AccessTokenAuthenticationConverter converter = new AccessTokenAuthenticationConverter();

    /**
     * Each role of BR-05 becomes the authority {@code hasRole} looks for, prefix included.
     *
     * <p>Driven from the enumeration rather than from two literals, so that a third role added
     * without a decision about its authority fails here rather than authorizing nothing in silence.
     */
    @ParameterizedTest(name = "{0} becomes ROLE_{0}")
    @EnumSource(UserRole.class)
    void BR05_eachRole_becomesTheMatchingPrefixedAuthority(UserRole role) {
        AbstractAuthenticationToken authenticated = converter.convert(tokenWithRole(role.name()));

        assertThat(authenticated.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(AccessTokenAuthenticationConverter.ROLE_PREFIX + role.name());
    }

    /** One role per account (BR-05), so one authority and never a second. */
    @Test
    void BR05_anAccount_holdsExactlyOneAuthority() {
        assertThat(converter.convert(tokenWithRole(UserRole.TRADER.name())).getAuthorities())
                .hasSize(1);
    }

    /**
     * A claim naming something that is not a role grants nothing.
     *
     * <p>The values below are the three shapes a defect produces: a role that was removed or never
     * existed, the authority spelled with the prefix already on it, and the right role in the wrong
     * case. None may be mapped through, because an authority nobody grants is harmless only until
     * somebody writes the rule that matches it.
     */
    @ParameterizedTest(name = "a role claim of \"{0}\" grants nothing")
    @ValueSource(strings = {"SUPERUSER", "ROLE_ADMIN", "admin", "", " ", "TRADER,ADMIN"})
    void BR05_aClaimThatIsNotARole_grantsNoAuthority(String claim) {
        assertThat(converter.convert(tokenWithRole(claim)).getAuthorities()).isEmpty();
    }

    /** A token with no role claim is a caller with no role, not a caller with every role. */
    @Test
    void BR05_aTokenWithoutTheClaim_grantsNoAuthority() {
        assertThat(converter.convert(tokenWithRole(null)).getAuthorities()).isEmpty();
    }

    /**
     * The principal keeps the account id from {@code sub}.
     *
     * <p>Asserted because it is what a service will compare against the owner of a row when
     * ownership is checked (SRS 4.2.4). A converter that rebuilt the principal from some other claim
     * would break every such comparison at once, and nothing about the roles would look wrong.
     */
    @Test
    void SRS424_thePrincipal_keepsTheAccountIdFromTheSubjectClaim() {
        UUID userId = UUID.randomUUID();

        AbstractAuthenticationToken authenticated = converter.convert(tokenWithSubject(userId.toString()));

        assertThat(authenticated.getName()).isEqualTo(userId.toString());
    }

    private Jwt tokenWithRole(String role) {
        Jwt.Builder builder = baseToken().subject(UUID.randomUUID().toString());
        if (role != null) {
            builder.claim(JwtConfig.ROLE_CLAIM, role);
        }
        return builder.build();
    }

    private Jwt tokenWithSubject(String subject) {
        return baseToken()
                .subject(subject)
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
    }

    private Jwt.Builder baseToken() {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        return Jwt.withTokenValue("token")
                .header("alg", JwtConfig.ALGORITHM.getName())
                .issuer(JwtConfig.ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(900))
                .claims(claims -> claims.putAll(Map.of()));
    }
}
