package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;

/**
 * What a tab of the Futures calculator works out (TR-25), each tab filling its own fields and leaving the others
 * {@code null}, as the exchange's calculator shows them. Nothing is stored.
 *
 * @param initialMargin PnL, liquidation: the initial margin at the entry, {@code Q × E / L}, in USDT
 * @param pnl PnL: the profit, negative for a loss, in USDT
 * @param roe PnL: the return on the initial margin, in percent
 * @param targetPrice target price: the exit price that makes the return asked; zero when no price does
 * @param liquidationPrice liquidation: rounded to the tick towards the position; zero when no price liquidates it
 * @param walletBalance liquidation: the margin the price was worked out with
 * @param maxQuantity max open: the largest quantity, on the pair's step
 * @param maxNotional max open: what that quantity is worth at the entry price, in USDT
 * @param maxPositionAtLeverage PnL, liquidation, max open: the largest notional the leverage allows
 * @param openPrice open price: the quantity-weighted mean of the parts
 * @param quantity open price: the parts' total quantity
 */
public record FuturesCalculatorResponse(
        BigDecimal initialMargin,
        BigDecimal pnl,
        BigDecimal roe,
        BigDecimal targetPrice,
        BigDecimal liquidationPrice,
        BigDecimal walletBalance,
        BigDecimal maxQuantity,
        BigDecimal maxNotional,
        BigDecimal maxPositionAtLeverage,
        BigDecimal openPrice,
        BigDecimal quantity) {}
