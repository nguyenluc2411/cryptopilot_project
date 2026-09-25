package com.cryptopilot.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The address a verification link should be sent to again (SRS 3.2.2).
 *
 * <p>Rule: SRS 3.2.2, messages MSG01, MSG02.
 */
public record ResendVerificationRequest(
        @NotBlank(message = "MSG01") @Email(message = "MSG02")
        String email) {}
