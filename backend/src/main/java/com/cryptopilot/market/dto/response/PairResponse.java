package com.cryptopilot.market.dto.response;

import java.math.BigDecimal;

/**
 * One pair enabled on a market (BR-07), as the market list of SCR-01 and SCR-09 shows it.
 *
 * <p>Rule: UC-09, BR-07; SRS 3.3.1.
 *
 * @param symbol the symbol, e.g. {@code BTCUSDT}
 * @param market {@code SPOT} or {@code FUTURES}
 * @param baseAsset the base coin's symbol
 * @param quoteAsset the quote coin's symbol
 * @param baseAssetName the base coin's name
 * @param logoUrl where the base coin's logo is, or absent
 * @param tickSize the price increment on this market, or absent before the first synchronisation
 * @param stepSize the quantity increment on this market, or absent before the first synchronisation
 * @param minNotional the smallest order value on this market, or absent before the first synchronisation
 * @param displayOrder the administrator's order, lowest first
 */
public record PairResponse(
        String symbol,
        String market,
        String baseAsset,
        String quoteAsset,
        String baseAssetName,
        String logoUrl,
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal minNotional,
        int displayOrder) {}
