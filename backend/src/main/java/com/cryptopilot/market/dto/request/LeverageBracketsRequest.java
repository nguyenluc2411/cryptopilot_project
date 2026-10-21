package com.cryptopilot.market.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The leverage brackets of a futures pair, replacing all of its current ones (SCR-38, UC-46), smallest notional
 * first. Binance publishes at most a few dozen per symbol.
 *
 * <p>Rule: UC-46; BR-26, BR-27, BR-31.
 *
 * @param brackets 1 to 50 rows, contiguous and non-overlapping
 */
public record LeverageBracketsRequest(
        @NotEmpty(message = "MSG01") @Size(max = 50, message = "MSG15")
        List<@NotNull(message = "MSG01") @Valid LeverageBracketRequest> brackets) {}
