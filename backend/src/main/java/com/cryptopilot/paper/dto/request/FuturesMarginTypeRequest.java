package com.cryptopilot.paper.dto.request;

import com.cryptopilot.paper.model.enums.MarginType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A change of a Futures pair's margin type. Binance: {@code POST /fapi/v1/marginType}.
 *
 * @param symbol the pair, e.g. {@code BTCUSDT}
 * @param marginType {@code ISOLATED} or {@code CROSSED}
 */
public record FuturesMarginTypeRequest(
        @NotBlank(message = "MSG01") @Size(max = 32, message = "MSG15")
        String symbol,

        @NotNull(message = "MSG01") MarginType marginType) {}
