package com.cryptopilot.market.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * One row of SCR-38. The bracket number is the row's place in the list, from 1.
 *
 * <p>Rule: UC-46; BR-26, BR-27.
 *
 * @param notionalFloor the lowest notional of the bracket, inclusive; 0 for the first, the previous cap for the others
 * @param notionalCap the highest notional, exclusive; above the floor
 * @param maxLeverage the highest leverage in the bracket, 1 to 125, never above the previous bracket's
 * @param maintenanceMarginRate between 0 and 1, both excluded
 * @param maintenanceAmount the maintenance amount; computed when absent, and when given it must be the value that keeps
 *     the maintenance margin continuous at the floor (BR-27)
 */
public record LeverageBracketRequest(
        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", message = "MSG15")
        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal notionalFloor,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal notionalCap,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG15") @Max(value = 125, message = "MSG15")
        Integer maxLeverage,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @DecimalMax(value = "1", inclusive = false, message = "MSG15")
        @Digits(integer = 4, fraction = 8, message = "MSG15")
        BigDecimal maintenanceMarginRate,

        @DecimalMin(value = "0", message = "MSG15") @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal maintenanceAmount) {}
