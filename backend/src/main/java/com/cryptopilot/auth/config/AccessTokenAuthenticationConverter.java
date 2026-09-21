package com.cryptopilot.auth.config;

import com.cryptopilot.user.UserRole;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a verified access token into an authenticated caller who holds one role.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>The resource server's default converter reads the {@code scope} and {@code scp} claims and
 * publishes them as {@code SCOPE_*} authorities. This application's tokens carry neither: they carry
 * {@code role}, written by {@link com.cryptopilot.auth.service.AccessTokenIssuer}. Without the
 * conversion below a valid token would authenticate a caller with an empty authority list, every
 * {@code hasRole(...)} rule in {@link SecurityConfig} would be false, and the whole authorization
 * matrix would refuse everybody — a failure that is safe but total, and silent in the sense that
 * nothing about the token, the signature or the configuration looks wrong.
 *
 * <h2>The role is checked against the enumeration, not merely prefixed</h2>
 *
 * <p>The claim is only granted when it names a constant of {@link UserRole}. The application signs
 * its own tokens, so a bad value cannot arrive from outside; it could only arrive from a defect that
 * wrote one. Mapping such a value straight through would invent an authority no rule mentions, and
 * an authority nobody grants is harmless right up until somebody writes the matching rule. Refusing
 * it here means the caller ends up with no authority and is denied, which is the direction to fail.
 *
 * <p>The same check is what keeps BR-05's two roles two. A third role reaches this class only after
 * it has been added to {@link UserRole}, to {@link com.cryptopilot.user.entity.Role} and to the
 * {@code ck_user_account_role} check constraint, which is three deliberate steps rather than a
 * string.
 *
 * <h2>Spring's prefix, stated once</h2>
 *
 * <p>{@code hasRole("ADMIN")} tests for an authority literally named {@code ROLE_ADMIN}; the prefix
 * is a convention of the expression, not part of the role. It is applied here, in the one place that
 * builds authorities, so that no rule elsewhere has to spell it and no two places can disagree about
 * whether it was already applied.
 *
 * <p>The principal keeps its name from the {@code sub} claim, which is the account's id. That is what
 * a service will compare against a row's owner when ownership is checked (SRS 4.2.4), so the
 * conversion deliberately changes nothing about it.
 *
 * <p>Rule: BR-05; SRS 3.1.3, SRS 4.2.4 (authorization is enforced on the server for every request);
 * TECHNICAL_DESIGN section 5.3.
 *
 * <p>Reference: Sandhu, R., Coyne, E., Feinstein, H. &amp; Youman, C. (1996). <i>Role-Based Access
 * Control Models</i>. IEEE Computer 29(2) (a session activates the roles assigned to a user; the
 * permissions follow from the role and are never carried by the user directly).
 * <p>Reference: Jones, M., Bradley, J. &amp; Sakimura, N. (2015). RFC 7519, <i>JSON Web Token</i>,
 * section 4.1.2 ({@code sub} identifies the principal the claims are about).
 */
@Component
public class AccessTokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** The prefix {@code hasRole(...)} expands to. Applied here and nowhere else. */
    static final String ROLE_PREFIX = "ROLE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        return new JwtAuthenticationToken(token, authoritiesOf(token), token.getSubject());
    }

    /**
     * The one authority the token grants, or none.
     *
     * <p>A list of at most one, because BR-05 gives an account exactly one role. Returning a
     * collection rather than a single value is the contract {@link JwtAuthenticationToken} takes,
     * not a hint that a second role is coming.
     */
    private static List<GrantedAuthority> authoritiesOf(Jwt token) {
        String claim = token.getClaimAsString(JwtConfig.ROLE_CLAIM);
        if (claim == null || !isKnownRole(claim)) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + claim));
    }

    private static boolean isKnownRole(String claim) {
        for (UserRole role : UserRole.values()) {
            if (role.name().equals(claim)) {
                return true;
            }
        }
        return false;
    }
}
