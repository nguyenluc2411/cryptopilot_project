package com.cryptopilot.paper.dto.request;

import com.cryptopilot.paper.model.enums.PositionDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The target price tab of the Futures calculator (TR-25): the exit price that makes a return on the initial margin.
 * Nothing is stored.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}, whose tick the price is rounded to
 * @param side LONG or SHORT
 * @param leverage 1 to the pair's highest
 * @param entryPrice the price it opens at
 * @param roe the return on the initial margin, in percent; negative for a loss
 */
public record FuturesTargetCalcRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG15")
        String symbol,

        @NotNull(message = "MSG01") PositionDirection side,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG15")
        Integer leverage,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 16, fraction = 12, message = "MSG15")
        BigDecimal entryPrice,

        @NotNull(message = "MSG01") @Digits(integer = 8, fraction = 4, message = "MSG15")
        BigDecimal roe) {}
