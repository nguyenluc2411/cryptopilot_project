package com.cryptopilot.market.dto.response;

/**
 * A symbol the exchange trades that is not yet a pair of the system, offered by SCR-37 to be added (UC-45).
 *
 * <p>Rule: UC-45; NSF-01.
 *
 * @param symbol e.g. {@code SOLUSDT}
 * @param baseAsset the base coin
 * @param quoteAsset the quote coin
 * @param spotTrading whether the exchange trades it on Spot
 * @param futuresTrading whether the exchange trades it as a USDⓈ-M perpetual
 */
public record ExchangeSymbolResponse(
        String symbol, String baseAsset, String quoteAsset, boolean spotTrading, boolean futuresTrading) {}
