package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.WalletType;
import java.math.BigDecimal;
import java.util.List;

/**
 * One wallet of the caller's paper account, largest value first.
 *
 * @param wallet SPOT or FUTURES
 * @param balances every coin the wallet holds or has held
 * @param estimatedUsdt the sum of the valued balances
 * @param estimatedBtc the same in BTC, or {@code null} when no current BTCUSDT price is known
 * @param complete whether every balance with a total was valued; when not, the estimates leave some out
 */
public record WalletResponse(
        WalletType wallet,
        List<BalanceResponse> balances,
        BigDecimal estimatedUsdt,
        BigDecimal estimatedBtc,
        boolean complete) {}
