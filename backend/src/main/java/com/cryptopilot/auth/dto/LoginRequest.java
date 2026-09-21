package com.cryptopilot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * What SCR-04 sends to sign in (SRS 3.2.3).
 *
 * <p>The password carries no pattern and no length rule, and that is not an omission. BR-02 governs
 * the password being <em>set</em>; a sign-in compares what was typed against what was stored, and an
 * account created before a rule changed must still be able to sign in and be told to change it.
 * Rejecting a sign-in at validation would also answer MSG01 where the SRS says MSG08 — and would say
 * that the password is at least the wrong shape, which is one bit more than "incorrect email or
 * password" is willing to give away.
 *
 * <p>Rule: SRS 3.2.3, UC-03; messages MSG01, MSG02.
 *
 * @param email the address the account signs in with
 * @param password the password as typed, compared with the configured encoder and never stored
 * <p>{@code rememberMe} is a boxed {@code Boolean} and is normalised here, because an unticked
 * checkbox is usually an absent field rather than {@code false}. A primitive component would make the
 * absence a parse failure — Jackson refuses to map a missing value onto a primitive — and the caller
 * would be answered "failed to read request" instead of being signed in. Absent means not remembered.
 *
 * @param rememberMe SRS 3.2.3: a thirty-day refresh token instead of a seven-day one; absent is the
 *     same as false
 */
public record LoginRequest(
        @NotBlank(message = "MSG01") @Email(message = "MSG02")
        String email,

        @NotBlank(message = "MSG01") String password,
        Boolean rememberMe) {

    public LoginRequest {
        rememberMe = rememberMe != null && rememberMe;
    }

    /** Names neither the address nor the password, so a bound request cannot be logged into a leak. */
    @Override
    public String toString() {
        return "LoginRequest(rememberMe=" + rememberMe + ")";
    }
}
