package com.cryptopilot.paper.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * The leverage brackets of one Futures pair. Binance: {@code GET /fapi/v1/leverageBracket}.
 *
 * @param symbol the pair
 * @param notionalCoef always 1: the account has the standard brackets
 * @param brackets the brackets, smallest notionals first
 */
public record FuturesLeverageBracketResponse(String symbol, BigDecimal notionalCoef, List<Bracket> brackets) {

    /**
     * One bracket.
     *
     * @param bracket its number, 1 for the smallest notionals
     * @param initialLeverage the highest leverage allowed in it
     * @param notionalCap the highest notional, exclusive
     * @param notionalFloor the lowest notional, inclusive
     * @param maintMarginRatio the maintenance margin rate
     * @param cum the maintenance amount
     */
    public record Bracket(
            int bracket,
            int initialLeverage,
            BigDecimal notionalCap,
            BigDecimal notionalFloor,
            BigDecimal maintMarginRatio,
            BigDecimal cum) {}
}
