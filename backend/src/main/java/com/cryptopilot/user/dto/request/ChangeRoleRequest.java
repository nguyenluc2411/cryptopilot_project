package com.cryptopilot.user.dto.request;

import com.cryptopilot.user.model.enums.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A new role for an account, with the reason SRS 3.11.2 requires.
 *
 * <p>Rule: UC-42; BR-05, BR-57, BR-58.
 *
 * @param role TRADER or ADMIN
 * @param reason 1 to 500 characters
 */
public record ChangeRoleRequest(
        @NotNull(message = "MSG01") Role role,

        @NotBlank(message = "MSG01") @Size(max = 500, message = "MSG15")
        String reason) {}
