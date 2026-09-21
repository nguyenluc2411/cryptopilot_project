package com.cryptopilot.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The refresh token presented to renew a session or to end one (SRS 3.2.3).
 *
 * <p>It is a credential, so it arrives in a body rather than in the query string of a URL that would
 * be written to every access log and proxy on the way — the same reason the verification token is in
 * a body.
 *
 * <p>Rule: SRS 3.2.3, UC-03, UC-05; message MSG01; TECHNICAL_DESIGN 7.15.
 *
 * @param refreshToken the opaque value issued by the last sign-in or refresh
 */
public record RefreshRequest(@NotBlank(message = "MSG01") String refreshToken) {

    /** Says that a token was supplied and not what it is. */
    @Override
    public String toString() {
        return "RefreshRequest(refreshToken=***)";
    }
}
