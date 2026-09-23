package com.cryptopilot.auth.config;

import java.time.Clock;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Refuses an access token at its expiry instant and after it, reading the injected clock.
 *
 * <h2>Why not Spring's own timestamp validator</h2>
 *
 * <p>Two reasons, and both are about a boundary being where the rule says it is. It allows sixty
 * seconds of clock skew by default — a sensible allowance for tokens issued by somebody else's
 * server, and the wrong one here, where one process signs and verifies and there are no two clocks to
 * disagree; sixty seconds on a fifteen-minute token is an extra 6.7% of life that SRS 3.2.3 does not
 * grant. And it treats {@code exp} as inclusive, accepting a token at the instant it expires, while
 * RFC 7519 says the current time must be <em>before</em> the expiration time.
 *
 * <p>One instant is not an exposure worth worrying about on its own. It is worth being exact about
 * because every other expiry in this application is exclusive — a verification link is not valid at
 * twenty-four hours, a lockout is over at fifteen minutes — and a single component with the opposite
 * convention is the one somebody reasons about wrongly later.
 *
 * <p>{@code nbf} is not validated, and there is nothing to validate: no token this application issues
 * carries one, and a token that does either was not signed with our key — in which case the signature
 * has already rejected it — or was, in which case whoever holds the key has no need of a "not before".
 *
 * <p>Rule: SRS 3.2.3; TECHNICAL_DESIGN section 5.3.
 *
 * <p>Reference: Jones, M., Bradley, J. &amp; Sakimura, N. (2015). RFC 7519, <i>JSON Web Token</i>,
 * section 4.1.4 ("the current date/time MUST be before the expiration time").
 * <p>Reference: Sheffer, Y., Hardt, D. &amp; Jones, M. (2020). RFC 8725, <i>JSON Web Token Best
 * Current Practices</i>, section 3.8 (always validate the expiry).
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AccessTokenExpiryValidator implements OAuth2TokenValidator<Jwt> {

    /** The error code RFC 6750 uses for a token a resource server will not accept. */
    private static final OAuth2Error EXPIRED = new OAuth2Error("invalid_token", "the access token has expired", null);

    /** A token with no expiry at all is refused: an unlimited session is not a session. */
    private static final OAuth2Error NO_EXPIRY =
            new OAuth2Error("invalid_token", "the access token carries no expiry", null);

    private final Clock clock;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Instant expiresAt = token.getExpiresAt();
        if (expiresAt == null) {
            return OAuth2TokenValidatorResult.failure(NO_EXPIRY);
        }
        return clock.instant().isBefore(expiresAt)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(EXPIRED);
    }
}
