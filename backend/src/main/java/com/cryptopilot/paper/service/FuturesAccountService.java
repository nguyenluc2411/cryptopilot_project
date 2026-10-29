package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.response.FuturesAccountResponse;
import com.cryptopilot.paper.dto.response.FuturesBalanceResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionRiskResponse;
import java.util.List;
import java.util.UUID;

/**
 * The paper Futures account and positions of TR-18 at the current mark prices, as the exchange's USDⓈ-M API reads
 * them: the positions with their liquidation price and margin, the account's margin and balances, and the balance in
 * short. Each needs the Futures feature of the Trader's plan and an opened paper account.
 *
 * <p>Rule: TR-18; D-127.
 */
public interface FuturesAccountService {

    /** The open positions, of one pair or of all. */
    List<FuturesPositionRiskResponse> positionRisk(UUID userId, String symbol);

    /** The account's margin and balances, with its open positions. */
    FuturesAccountResponse account(UUID userId);

    /** The balance of the one asset, USDT. */
    List<FuturesBalanceResponse> balance(UUID userId);
}
