package com.cryptopilot.paper.dto.request;

import com.cryptopilot.paper.model.enums.PositionSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Margin moved into or out of an isolated position. Binance: {@code POST /fapi/v1/positionMargin}.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param positionSide BOTH, or absent, in one-way mode
 * @param amount the USDT moved, more than zero (the exchange's {@code -4055} otherwise)
 * @param type 1 to add margin, 2 to reduce it
 */
public record FuturesPositionMarginRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG15")
        String symbol,

        PositionSide positionSide,

        @NotNull(message = "MSG01")
        @DecimalMin(value = "0", inclusive = false, message = "MSG15")
        @Digits(integer = 20, fraction = 8, message = "MSG15")
        BigDecimal amount,

        @NotNull(message = "MSG01") @Min(value = 1, message = "MSG15") @Max(value = 2, message = "MSG15")
        Integer type) {

    /** Whether margin is added rather than taken out. */
    public boolean adding() {
        return type == 1;
    }
}
