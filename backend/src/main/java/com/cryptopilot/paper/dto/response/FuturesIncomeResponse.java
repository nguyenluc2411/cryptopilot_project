package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One change of the paper Futures wallet, with the exchange's field names. Binance: an item of
 * {@code GET /fapi/v1/income}. {@code INSURANCE_COVER} is CryptoPilot's own type: the loss beyond a position's margin
 * the insurance fund took, written so the wallet always equals its history.
 *
 * @param symbol the pair of the position it comes from; empty for a transfer
 * @param incomeType TRANSFER, REALIZED_PNL, COMMISSION, FUNDING_FEE, INSURANCE_CLEAR (the liquidation clearance fee) or
 *     INSURANCE_COVER
 * @param income the signed change, in USDT
 * @param asset USDT
 * @param info what it is, the CryptoPilot ledger type
 * @param time when it was written
 * @param tranId the ledger entry
 * @param tradeId the fill it comes from, or {@code null}
 */
public record FuturesIncomeResponse(
        String symbol,
        String incomeType,
        BigDecimal income,
        String asset,
        String info,
        Instant time,
        UUID tranId,
        UUID tradeId) {}
