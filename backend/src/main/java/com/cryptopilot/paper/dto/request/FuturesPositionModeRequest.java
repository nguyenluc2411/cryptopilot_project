package com.cryptopilot.paper.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * A change of the account's position mode. Binance: {@code POST /fapi/v1/positionSide/dual}.
 *
 * @param dualSidePosition {@code true} for hedge mode (a LONG and a SHORT position per pair), {@code false} for
 *     one-way mode
 */
public record FuturesPositionModeRequest(
        @NotNull(message = "MSG01") Boolean dualSidePosition) {}
