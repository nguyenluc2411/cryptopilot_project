package com.cryptopilot.market.dto.request;

import com.cryptopilot.market.model.enums.MarketType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An administrator stops the price replay of a pair (RF-07), which takes the exchange's prices again.
 *
 * @param market the pair's market
 * @param symbol the pair, e.g. {@code BTCUSDT}
 */
public record ReplayStopRequest(
        @NotNull(message = "MSG01") MarketType market,

        @NotBlank(message = "MSG01") @Size(max = 30, message = "MSG01")
        String symbol) {}
