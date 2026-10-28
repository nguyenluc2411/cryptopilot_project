package com.cryptopilot.paper.model;

import java.math.BigDecimal;

/**
 * The derived balances of a Futures account: nothing is locked per order, so each is recomputed from the cross
 * wallet, the unrealized profit of the cross positions and the margin they and the open orders need.
 *
 * <p>Rule: TR-15, TR-18; D-127.
 *
 * @param availableBalance what a cross order can use, unrealized profit included, never below zero
 * @param crossFreeCash what an isolated wallet can be funded from and what can be withdrawn, unrealized profit
 *     excluded, never below zero
 * @param maxWithdrawAmount what can leave the Futures wallet, never more than either of the two above
 */
public record FuturesAccountMargin(
        BigDecimal availableBalance, BigDecimal crossFreeCash, BigDecimal maxWithdrawAmount) {}
