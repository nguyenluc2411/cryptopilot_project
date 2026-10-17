package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.WalletType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One coin of a paper wallet as a change left it, pushed on {@code /user/queue/balances} (TR-08). Only the coins a
 * change touched are pushed; the client puts each over the row of the wallet it already holds. No value in USDT is
 * pushed: the client values a balance at the prices it already receives.
 *
 * @param wallet SPOT or FUTURES
 * @param coinId the coin
 * @param asset its symbol, e.g. {@code BTC}
 * @param free the amount free to trade or transfer
 * @param locked the amount held by open orders
 * @param total free plus locked
 */
public record BalanceUpdateResponse(
        WalletType wallet, UUID coinId, String asset, BigDecimal free, BigDecimal locked, BigDecimal total) {}
