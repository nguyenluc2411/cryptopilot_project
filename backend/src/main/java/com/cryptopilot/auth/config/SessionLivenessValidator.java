package com.cryptopilot.auth.config;

import com.cryptopilot.auth.service.LiveSessions;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Refuses an access token whose session has ended (D-33).
 *
 * <p>Runs in the resource server's decoder after the signature, the expiry and the issuer, so it is
 * asked only about tokens this application issued and that are otherwise valid. A refusal is an
 * {@code invalid_token}, which the filter chain answers with the same 401 and MSG44 body as any other
 * unusable credential — a caller whose session was ended is told to sign in again, which is exactly
 * what MSG44 says.
 *
 * <p>A token without a {@code sid} claim is accepted. Only a token issued before the claim existed
 * lacks one, every such token has expired fifteen minutes after the release that added it, and
 * nothing this application issues since can omit it; refusing them would sign everybody out on the
 * day of the release for no protection that lasts longer than those fifteen minutes. A {@code sid}
 * that is not an identifier is refused.
 *
 * <p>Rule: SRS 3.2.3, 3.2.5, 4.2.4; D-33; TECHNICAL_DESIGN 5.3.
 *
 * <p>Reference: OWASP Application Security Verification Standard 4.0.3, requirement V3.3.3 (terminate
 * the other active sessions after a password change).
 * <p>Reference: Jones, M. (2022). <i>OpenID Connect Front-Channel Logout 1.0</i>, section 3 (the
 * {@code sid} claim).
 * <p>Reference: Jones, M. &amp; Hardt, D. (2012). RFC 6750, <i>The OAuth 2.0 Authorization Framework:
 * Bearer Token Usage</i>, section 3.1 ({@code invalid_token}: the token is expired, revoked or
 * otherwise invalid).
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class SessionLivenessValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ENDED =
            new OAuth2Error("invalid_token", "the session this access token belongs to has ended", null);

    private final LiveSessions liveSessions;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String sid = token.getClaimAsString(JwtConfig.SESSION_CLAIM);
        if (sid == null) {
            return OAuth2TokenValidatorResult.success();
        }
        try {
            return liveSessions.isAlive(UUID.fromString(sid), UUID.fromString(token.getSubject()))
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(ENDED);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
    }
}
