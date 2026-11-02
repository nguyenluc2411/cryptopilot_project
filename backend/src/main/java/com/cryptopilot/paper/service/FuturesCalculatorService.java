package com.cryptopilot.paper.service;

import com.cryptopilot.paper.dto.request.FuturesLiquidationCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesMaxOpenCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesOpenPriceCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesPnlCalcRequest;
import com.cryptopilot.paper.dto.request.FuturesTargetCalcRequest;
import com.cryptopilot.paper.dto.response.FuturesCalculatorResponse;
import java.util.UUID;

/**
 * The five tabs of the Futures calculator of the exchange's app (TR-25, plan 12.3): PnL, target price, liquidation
 * price, max open and open price. Each works out figures from what it is given and the pair's brackets and filters,
 * with the arithmetic orders and positions use, and stores nothing. Each needs the Futures feature of the Trader's
 * plan; no paper account.
 *
 * <p>Rule: TR-25; D-127.
 */
public interface FuturesCalculatorService {

    /** The initial margin, profit and return of a position between two prices. */
    FuturesCalculatorResponse pnl(UUID userId, FuturesPnlCalcRequest request);

    /** The exit price that makes a return on the initial margin. */
    FuturesCalculatorResponse target(UUID userId, FuturesTargetCalcRequest request);

    /** The liquidation price of one position alone in its margin. */
    FuturesCalculatorResponse liquidation(UUID userId, FuturesLiquidationCalcRequest request);

    /** The largest position a balance opens with a LIMIT order at a price. */
    FuturesCalculatorResponse maxOpen(UUID userId, FuturesMaxOpenCalcRequest request);

    /** The entry price of a position opened in several parts. */
    FuturesCalculatorResponse openPrice(UUID userId, FuturesOpenPriceCalcRequest request);
}
