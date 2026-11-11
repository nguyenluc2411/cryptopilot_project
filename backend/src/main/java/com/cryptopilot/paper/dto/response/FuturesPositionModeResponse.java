package com.cryptopilot.paper.dto.response;

/**
 * The Trader's position mode. Binance: {@code GET /fapi/v1/positionSide/dual}.
 *
 * @param dualSidePosition true in hedge mode, false in one-way mode
 */
public record FuturesPositionModeResponse(boolean dualSidePosition) {}
