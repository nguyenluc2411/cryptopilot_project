package com.cryptopilot.auth.dto.response;

import com.cryptopilot.user.UserRole;
import java.time.Instant;

/**
 * The answer to a sign-in or a refresh: the pair of tokens and when each stops working (SRS 3.2.3).
 *
 * <p>It carries the role because SRS 3.2.3 routes a Trader to SCR-08 and an Admin to SCR-33, and the
 * client cannot know which without being told. It carries no address, no display name and no account
 * key: a response to a sign-in is not a way to read a profile, and the screens that need one ask for
 * it.
 *
 * <p>The expiries are absolute instants rather than a number of seconds, because a client that
 * schedules a refresh against "expires in 900" has to know when it started counting, and the two
 * clocks are not the same clock.
 *
 * <p>Nothing here knows how a session is built. The controller maps the service's result onto this
 * shape, because a response record that could read a service type would be a layer rule broken in the
 * one place it is easiest to break it.
 *
 * <p>Rule: SRS 3.2.3, UC-03; TECHNICAL_DESIGN sections 3.1, 5.3 and 8.
 *
 * @param accessToken the signed JWT to send as {@code Authorization: Bearer ...}
 * @param accessTokenExpiresAt when it stops being accepted
 * @param refreshToken the value to present in order to obtain the next pair
 * @param refreshTokenExpiresAt when that stops working
 * @param role the role the account holds, which decides where the client goes next
 */
public record SessionResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserRole role) {

    /** Neither token, for the same reason the request hides one. */
    @Override
    public String toString() {
        return "SessionResponse(" + role + ", access until " + accessTokenExpiresAt + ")";
    }
}
