package com.cryptopilot.auth.service;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.auth.config.TokenProperties;
import com.cryptopilot.common.util.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Makes the access token a client sends with every request: a signed JWT that says who the caller is
 * and until when.
 *
 * <h2>What is in it, and what is deliberately not</h2>
 *
 * <p>Subject, role, issuer, issued-at, expiry, and a token identifier. Nothing else. An access token
 * is a bearer credential — whoever holds it is the caller — and it is readable by anyone who holds
 * it, because a signature proves a payload was not altered and does nothing to hide it. So no
 * address, no display name, no subscription state, nothing about the person beyond the key that
 * identifies their account.
 *
 * <p>The role is in the token because authorization has to be decided without a database read on
 * every request, and because it is the claim T-015's policies will read. The cost of putting it here
 * is stated rather than discovered later: a role changed by an administrator does not take effect
 * until the holder's current access token expires, at most fifteen minutes later. BR-06's sessions
 * are revoked by revoking the refresh tokens, which stops the session being renewed; it cannot recall
 * an access token that is already out.
 *
 * <p>Every instant comes from the injected clock, so a test places a token exactly on either side of
 * its expiry instead of waiting a quarter of an hour for one.
 *
 * <p>Rule: SRS 3.2.3 (fifteen minutes); TECHNICAL_DESIGN section 5.3.
 *
 * <p>Reference: Jones, M., Bradley, J. &amp; Sakimura, N. (2015). RFC 7519, <i>JSON Web Token</i>,
 * section 4.1 (the registered claims {@code iss}, {@code sub}, {@code exp}, {@code iat} and
 * {@code jti}).
 * <p>Reference: Sheffer, Y., Hardt, D. &amp; Jones, M. (2020). RFC 8725, <i>JSON Web Token Best
 * Current Practices</i>, section 3.1 (state the algorithm rather than trusting the header) and
 * section 3.8 (always set and validate an expiry).
 */
@Component
public class AccessTokenIssuer {

    /** The claim T-015's policies read. Named here because this is where it is written. */
    static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final TokenProperties properties;
    private final Clock clock;

    AccessTokenIssuer(JwtEncoder encoder, TokenProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A signed access token for this account, valid for the configured window from now.
     *
     * <p>The algorithm is put in the header explicitly rather than left to the encoder's default, so
     * that the token this issues and the token the decoder accepts are the same kind by construction.
     *
     * @param role the role the account holds, as the claim T-015 will authorize on
     */
    public IssuedAccessToken issueFor(UUID userId, String role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UuidV7.next().toString())
                .claim(ROLE_CLAIM, role)
                .build();
        JwsHeader header = JwsHeader.with(JwtConfig.ALGORITHM).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, expiresAt);
    }

    /**
     * A signed access token and when it stops being accepted.
     *
     * <p>The expiry travels beside the token although it is also inside it, so that a client is not
     * obliged to parse a credential it is only supposed to carry.
     */
    public record IssuedAccessToken(String value, Instant expiresAt) {}
}
