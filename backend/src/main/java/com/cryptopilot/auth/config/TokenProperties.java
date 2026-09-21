package com.cryptopilot.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long each kind of session token lives, and the key the access token is signed with.
 *
 * <h2>Why these are configuration and the lockout window is not</h2>
 *
 * <p>BR-03's five attempts and fifteen minutes sit as constants beside the rule, because the rule
 * states them and changing one changes the rule. These three durations come from SRS 3.2.3, which
 * states them too — so the defaults below are the SRS values and nothing else, and a test asserts
 * them as declared values rather than inferring them from an observed expiry. What makes them
 * configuration all the same is operational: an incident that requires every access token in the
 * estate to age out faster is answered by a restart with a different value, not by a release, and
 * the same is not true of a business rule about how many passwords somebody may get wrong.
 *
 * <h2>The key</h2>
 *
 * <p>{@code secret} has no default, anywhere. A default signing key in a committed file is a key
 * every reader of the repository holds, and a token signed with it is a session on any deployment
 * that forgot to override it, so the application refuses to start rather than start insecurely
 * (D-20). Thirty-two characters is not a policy choice either: HS256 is HMAC-SHA-256, whose key must
 * be at least as long as the digest it produces, and a shorter one is rejected outright by the
 * library.
 *
 * <p>Rule: SRS 3.2.3; TECHNICAL_DESIGN sections 5.3 and 12; D-20.
 *
 * <p>Reference: Jones, M., Bradley, J. &amp; Sakimura, N. (2015). RFC 7519, <i>JSON Web Token</i>,
 * section 4.1.4 (the {@code exp} claim).
 * <p>Reference: Sheffer, Y., Hardt, D. &amp; Jones, M. (2020). RFC 8725, <i>JSON Web Token Best
 * Current Practices</i>, sections 3.5 and 3.8 (use a key of appropriate strength; always validate
 * the expiry).
 *
 * @param accessTokenTtl how long an access token is accepted for — SRS 3.2.3: fifteen minutes
 * @param refreshTokenTtl how long a refresh token is accepted for — SRS 3.2.3: seven days
 * @param rememberMeRefreshTokenTtl the same, when the sign-in asked to be remembered — thirty days
 * @param secret the HS256 signing key, supplied by the environment and never by a committed file
 */
@Validated
@ConfigurationProperties(prefix = "cryptopilot.auth.token")
public record TokenProperties(
        @NotNull @DefaultValue("15m") Duration accessTokenTtl,
        @NotNull @DefaultValue("7d") Duration refreshTokenTtl,
        @NotNull @DefaultValue("30d") Duration rememberMeRefreshTokenTtl,

        @NotBlank @Size(min = 32, message = "an HS256 key is at least 256 bits, so at least 32 characters")
        String secret) {

    /**
     * The window a refresh token gets when it is issued.
     *
     * <p>Written here rather than at each call site so that the two branches of "Remember me" are one
     * decision in one place — the alternative is a ternary repeated at every sign-in and every
     * rotation, and the day one of them is written the wrong way round is the day a thirty-day
     * session quietly becomes a seven-day one for half the users.
     */
    public Duration refreshTokenTtl(boolean rememberMe) {
        return rememberMe ? rememberMeRefreshTokenTtl : refreshTokenTtl;
    }
}
