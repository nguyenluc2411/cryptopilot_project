package com.cryptopilot.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a client presents to end its session (SRS UC-05, section 3.2.3), and for the mobile
 * application the device it is signing out of (SRS 3.2.5).
 *
 * <p>The refresh token is the credential, exactly as in {@link RefreshRequest}. The messaging token
 * is optional: the web client has none, and a mobile application that sends one has its push
 * notifications stopped in the same transaction that ends the session, which is what SRS 3.2.5 asks
 * for ("deactivates it on logout"). Carrying it here rather than in a second call means it still
 * happens for an application whose access token has already expired, because signing out needs only
 * the refresh token.
 *
 * <p>Rule: SRS UC-05, sections 3.2.3 and 3.2.5; message MSG01.
 *
 * @param refreshToken the opaque value issued by the last sign-in or refresh
 * @param fcmToken the messaging token of this installation, or {@code null} when there is none
 */
public record LogoutRequest(
        @NotBlank(message = "MSG01") String refreshToken,
        @Size(max = 512, message = "MSG01") String fcmToken) {

    /** Says that tokens were supplied and not what they are. */
    @Override
    public String toString() {
        return "LogoutRequest(refreshToken=***" + (fcmToken == null ? ")" : ", fcmToken=***)");
    }
}
