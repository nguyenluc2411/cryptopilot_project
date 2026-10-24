package com.cryptopilot.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A new value for a system setting (SCR-43), as text in the form of the setting's type: {@code 0.001} for a DECIMAL,
 * {@code 15} for an INT, {@code true} for a BOOLEAN.
 *
 * <p>Rule: SRS 3.11.8, UC-51.
 *
 * @param value 1 to 500 characters, the column's width
 */
public record UpdateSettingRequest(
        @NotBlank(message = "MSG01") @Size(max = 500, message = "MSG15")
        String value) {}
