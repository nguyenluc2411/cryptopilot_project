package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One margin change the Trader made to an isolated position. Binance: an item of
 * {@code GET /fapi/v1/positionMargin/history}.
 *
 * @param symbol the pair
 * @param type 1 added, 2 reduced
 * @param deltaType always {@code USER_ADJUST}: changes made by fills, funding or liquidation are not listed
 * @param amount the USDT moved, positive
 * @param asset USDT
 * @param positionSide BOTH in one-way mode
 * @param isolatedWallet the isolated wallet afterwards (CryptoPilot's addition)
 * @param time when it was made
 */
public record FuturesMarginHistoryResponse(
        String symbol,
        int type,
        String deltaType,
        BigDecimal amount,
        String asset,
        PositionSide positionSide,
        BigDecimal isolatedWallet,
        Instant time) {}
