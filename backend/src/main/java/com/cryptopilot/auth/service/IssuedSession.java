package com.cryptopilot.auth.service;

import com.cryptopilot.user.UserRole;
import java.time.Instant;
import java.util.UUID;

/**
 * What a sign-in or a refresh produces: the pair of tokens, when each stops working, and the little
 * the client needs to know about who it just signed in.
 *
 * <p>The expiries travel beside the tokens although the access token also carries its own inside it,
 * so that a client can schedule a refresh without parsing a credential it is only meant to carry —
 * and so that the refresh token, which is opaque and carries nothing at all, has an expiry too.
 *
 * <p>The role is here because SRS 3.2.3 routes a Trader and an Admin to different screens, and the
 * client cannot know which without being told. Nothing else about the account is on it: this record
 * is built from what the sign-in already knows and is not a way to read a profile.
 *
 * <p>It never leaves the application as it is. The controller maps it to a response, and a value that
 * should not cross the wire is dropped there rather than never being here — which is what happens to
 * {@link #userId}, present so that logging and a future device registration can name the account
 * without decoding the token.
 *
 * <p>Rule: SRS 3.2.3, UC-03; TECHNICAL_DESIGN section 5.3.
 *
 * @param accessToken the signed JWT sent with every request
 * @param accessTokenExpiresAt when it stops being accepted (SRS 3.2.3: fifteen minutes)
 * @param refreshToken the opaque value that buys a new pair, stored here only as a digest
 * @param refreshTokenExpiresAt when that stops working (seven days, or thirty with Remember me)
 * @param role the role the account holds, which decides where the client goes next
 * @param userId the account the session belongs to
 */
public record IssuedSession(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserRole role,
        UUID userId) {

    /** Names the account and neither of the two tokens, so a log line cannot print a credential. */
    @Override
    public String toString() {
        return "IssuedSession(" + userId + ", " + role + ", until " + refreshTokenExpiresAt + ")";
    }
}
