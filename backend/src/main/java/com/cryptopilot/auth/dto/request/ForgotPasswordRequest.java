package com.cryptopilot.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The address SCR-05 asks for a reset link (SRS 3.2.4).
 *
 * <p>The address is still validated as an address, even though the endpoint answers MSG12 whatever
 * happens. A syntactically impossible address is a mistake in the form rather than a question about
 * who holds an account, so telling the person about it reveals nothing and saves them a wait for a
 * mail that was never going to arrive.
 *
 * <p>Rule: SRS 3.2.4, messages MSG01, MSG02.
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "MSG01") @Email(message = "MSG02")
        String email) {}
