package com.cryptopilot.paper.dto.request;

import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.PositionDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The liquidation price tab of the Futures calculator (TR-25): where one position alone in its margin would be
 * liquidated, with the pair's brackets. Nothing is stored.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param side LONG or SHORT
 * @param marginType ISOLATED or CROSSED
 * @param leverage 1 to the pair's highest
 * @param entryPrice the price it opens at
 * @param quantity the base quantity
 * @param walletBalance the margin behind it: the cross wallet, required for CROSSED; the isolated margin for
 *     ISOLATED, its initial margin when absent
 */
public record FuturesLiquidationCalcRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG15")
        String symbol,

        @NotNull(message = "MSG01") PositionDirection side,

        @NotNull(message = "MSG01") MarginType marginType,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG15")
        Integer leverage,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal entryPrice,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal quantity,

        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal walletBalance) {}
