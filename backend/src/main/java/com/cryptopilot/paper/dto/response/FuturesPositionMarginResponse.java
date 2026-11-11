package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;

/**
 * The answer to a margin change of an isolated position. Binance: {@code POST /fapi/v1/positionMargin}, with the
 * isolated wallet afterwards as CryptoPilot's addition.
 *
 * @param amount the USDT moved
 * @param code always 200, as the exchange answers
 * @param msg the exchange's message
 * @param type 1 added, 2 reduced
 * @param isolatedWallet the position's isolated wallet afterwards
 */
public record FuturesPositionMarginResponse(
        BigDecimal amount, int code, String msg, int type, BigDecimal isolatedWallet) {}
