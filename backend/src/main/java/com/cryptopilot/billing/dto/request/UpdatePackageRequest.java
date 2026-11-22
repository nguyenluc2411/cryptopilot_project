package com.cryptopilot.billing.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The editable fields of a package (SCR-39, UC-42). The code and the tier never change. The FREE package takes a new
 * name only: its price, duration and sale flag are fixed, so they must be left out for it, and are required for a paid
 * package.
 *
 * <p>Rule: SRS 3.11.5; BR-62, BR-63; UC-42.
 *
 * @param name the name the Traders see, at most 100 characters
 * @param price the price in VND, a whole number above 0; {@code null} for FREE
 * @param durationDays 1–366; {@code null} for FREE
 * @param purchasable whether Traders can buy it; {@code null} for FREE
 */
public record UpdatePackageRequest(
        @NotBlank(message = "MSG01") @Size(max = 100, message = "MSG01")
        String name,

        @Min(value = 1, message = "MSG15") @Max(value = 999_999_999_999L, message = "MSG15")
        Long price,

        @Min(value = 1, message = "MSG15") @Max(value = 366, message = "MSG15")
        Integer durationDays,

        Boolean purchasable) {}
