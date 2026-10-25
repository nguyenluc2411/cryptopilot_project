package com.cryptopilot.trading.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The new values of a DRAFT plan (UC-17); the pair stays the one the plan was created on. Only the shape is checked here; the rules of BR-21 to BR-30 are the calculation's.
 * There is no capital: the plan takes the profile's default capital again at this save (D-121).
 *
 * <p>Rule: SRS 3.5.1; BR-30 (ranges), BR-68, BR-69, BR-70; D-111, D-112, D-121.
 *
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT; a Spot plan is LONG (BR-21)
 * @param entryType LIMIT, MARKET or STOP
 * @param entryPrice required for LIMIT, the stop price of a STOP; ignored for MARKET, which takes the current price
 * @param stopLoss the stop loss
 * @param takeProfit the take profit
 * @param riskPercent 0.1 to 10, or {@code null} for the profile's default
 * @param leverage 1 or more on Futures, {@code null} for 1; 1 or {@code null} on Spot
 * @param expiresAt when the ACTIVE plan expires unfilled; {@code null} is 7 days for LIMIT and STOP, none for MARKET
 * @param note the Trader's note
 * @param invalidationPrice the price that invalidates a waiting LIMIT or STOP plan (BR-68); none on MARKET
 * @param maxEntryDeviationR the plan's own limit on the entry deviation in R, at most the system's (BR-69)
 * @param analysisTimeframe {@code 15m}, {@code 1h}, {@code 4h} or {@code 1d}; {@code null} for the style preset's
 * @param activate whether to activate right after saving
 * @param confirmDeviation {@code true} once the Trader confirmed the MSG51 of a MARKET plan's entry deviation; only
 *     read when activating (BR-69)
 */
public record UpdateTradingPlanRequest(
        @NotNull(message = "MSG01") MarketType market,

        @NotNull(message = "MSG01") Direction direction,

        @NotNull(message = "MSG01") EntryType entryType,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal entryPrice,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal stopLoss,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal takeProfit,

        @DecimalMin(value = "0.1", message = "MSG15")
        @DecimalMax(value = "10", message = "MSG15")
        @Digits(integer = 2, fraction = 3, message = "MSG15")
        BigDecimal riskPercent,

        @Min(value = 1, message = "MSG15") Integer leverage,

        Instant expiresAt,

        @Size(max = 1000, message = "MSG15") String note,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal invalidationPrice,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 4, fraction = 8, message = "MSG15")
        BigDecimal maxEntryDeviationR,

        String analysisTimeframe,

        Boolean activate,

        Boolean confirmDeviation)
        implements PlanInputs {}
