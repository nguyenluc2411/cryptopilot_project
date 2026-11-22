package com.cryptopilot.billing.dto.request;

import com.cryptopilot.billing.model.enums.PlanTier;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new paid package (SCR-39, UC-42). It takes the limits its tier has now (BR-62): limits are edited per tier, not
 * per package. There is exactly one FREE package, so the tier is PRO or PREMIUM.
 *
 * <p>Rule: SRS 3.11.5; BR-62, BR-63; UC-42.
 *
 * @param code the unique code, upper case letters, digits and underscores, e.g. {@code PRO_QUARTERLY}
 * @param name the name the Traders see, at most 100 characters
 * @param tier PRO or PREMIUM
 * @param price the price in VND, a whole number above 0
 * @param durationDays 1–366
 * @param purchasable whether Traders can buy it
 */
public record CreatePackageRequest(
        @NotBlank(message = "MSG01")
        @Size(max = 32, message = "MSG01")
        @Pattern(regexp = "[A-Z0-9_]+", message = "MSG01")
        String code,

        @NotBlank(message = "MSG01") @Size(max = 100, message = "MSG01")
        String name,

        @NotNull(message = "MSG01") PlanTier tier,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG15") @Max(value = 999_999_999_999L, message = "MSG15")
        Long price,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG15") @Max(value = 366, message = "MSG15")
        Integer durationDays,

        @NotNull(message = "MSG01") Boolean purchasable) {}
