package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.MarginType;
import java.math.BigDecimal;

/**
 * The Trader's settings of one Futures pair. Binance: {@code GET /fapi/v1/symbolConfig}.
 *
 * @param symbol the pair
 * @param marginType {@code CROSSED} or {@code ISOLATED}
 * @param isAutoAddMargin always false: the simulation never adds margin by itself
 * @param leverage the leverage
 * @param maxNotionalValue the largest notional a position may reach at this leverage
 */
public record FuturesSymbolConfigResponse(
        String symbol, MarginType marginType, boolean isAutoAddMargin, int leverage, BigDecimal maxNotionalValue) {}
