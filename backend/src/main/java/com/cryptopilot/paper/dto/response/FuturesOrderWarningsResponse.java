package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.FuturesWarning;
import java.math.BigDecimal;
import java.util.List;

/**
 * The risk warnings of an order the Trader is about to place on the Futures form (TR-26), with the figures they were
 * worked out from. Nothing is stored or placed.
 *
 * @param cost what the order would cost, initial margin plus open loss; zero for an order that only reduces
 * @param budget the balance the cost is paid from: available for a cross pair, the cross wallet's free cash for an
 *     isolated one
 * @param liquidationPrice the estimated liquidation price of the position once the order filled; zero for none, or
 *     for an order that only reduces
 * @param warnings the warnings, the most severe first; empty when none, always for an order that only reduces
 */
public record FuturesOrderWarningsResponse(
        BigDecimal cost, BigDecimal budget, BigDecimal liquidationPrice, List<FuturesWarning> warnings) {}
