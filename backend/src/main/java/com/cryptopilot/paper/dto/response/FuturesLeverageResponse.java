package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;

/**
 * A pair's leverage after a change. Binance: {@code POST /fapi/v1/leverage}.
 *
 * @param symbol the pair
 * @param leverage the leverage now
 * @param maxNotionalValue the largest notional a position may reach at this leverage
 */
public record FuturesLeverageResponse(String symbol, int leverage, BigDecimal maxNotionalValue) {}
