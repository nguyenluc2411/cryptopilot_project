package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;

/**
 * The commission of a pair, as the exchange's {@code GET /fapi/v1/commissionRate}: zero until the admin configures it
 * (plan 2.3).
 *
 * @param symbol the pair
 * @param makerCommissionRate the share of a fill's value a waiting order pays
 * @param takerCommissionRate the share of a fill's value an order taking the book pays
 */
public record FuturesCommissionRateResponse(
        String symbol, BigDecimal makerCommissionRate, BigDecimal takerCommissionRate) {}
