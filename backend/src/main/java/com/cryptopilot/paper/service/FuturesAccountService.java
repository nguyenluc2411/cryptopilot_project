package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.response.FuturesAccountResponse;
import com.cryptopilot.paper.dto.response.FuturesBalanceResponse;
import com.cryptopilot.paper.dto.response.FuturesIncomeResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionHistoryResponse;
import com.cryptopilot.paper.dto.response.FuturesPositionRiskResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The paper Futures account and positions of TR-18 at the current mark prices, as the exchange's USDⓈ-M API reads
 * them: the positions with their liquidation price and margin, the account's margin and balances, and the balance in
 * short; and the wallet's history and the closed positions (TR-19). Each needs the Futures feature of the Trader's
 * plan and an opened paper account.
 *
 * <p>Rule: TR-18, TR-19; D-127.
 */
public interface FuturesAccountService {

    /** The open positions, of one pair or of all. */
    List<FuturesPositionRiskResponse> positionRisk(UUID userId, String symbol);

    /** The account's margin and balances, with its open positions. */
    FuturesAccountResponse account(UUID userId);

    /** The balance of the one asset, USDT. */
    List<FuturesBalanceResponse> balance(UUID userId);

    /**
     * The changes of the Futures wallet written in {@code [startTime, endTime)}, oldest first, of one pair or of all
     * and of one income type or of all (TR-19).
     *
     * @param limit how many, the latest; 1 to 1000, 100 when absent
     */
    List<FuturesIncomeResponse> income(
            UUID userId, String symbol, String incomeType, Instant startTime, Instant endTime, Integer limit);

    /**
     * The positions closed in {@code [startTime, endTime)}, newest first, of one pair or of all (TR-19).
     *
     * @param limit how many; 1 to 1000, 100 when absent
     */
    List<FuturesPositionHistoryResponse> positionHistory(
            UUID userId, String symbol, Instant startTime, Instant endTime, Integer limit);
}
