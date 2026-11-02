package com.cryptopilot.paper.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A change of a Futures pair's leverage. Binance: {@code POST /fapi/v1/leverage}.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param leverage the new leverage, from 1 to the pair's highest; out of range is refused with {@code -4028}
 */
public record FuturesLeverageRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG15")
        String symbol,

        @NotNull(message = "MSG01") Integer leverage) {}
