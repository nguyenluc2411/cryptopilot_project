package com.cryptopilot.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why an administrator locks, unlocks or bans an account (SRS 3.11.2: every action "requires a reason"). Recorded in
 * the audit entry.
 *
 * <p>Rule: UC-42; BR-57.
 *
 * @param reason 1 to 500 characters
 */
public record AccountActionRequest(
        @NotBlank(message = "MSG01") @Size(max = 500, message = "MSG15")
        String reason) {}
