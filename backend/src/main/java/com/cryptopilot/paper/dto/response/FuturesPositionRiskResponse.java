package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One open paper Futures position at the current mark price, with the exchange's field names. Binance: an item of
 * {@code GET /fapi/v3/positionRisk}. {@code marginType}, {@code leverage} (from the exchange's v2) and
 * {@code marginRatio} are CryptoPilot's additions, so the positions table needs no second call.
 *
 * @param symbol the pair
 * @param positionSide BOTH in one-way mode
 * @param positionAmt the size, negative for a short
 * @param entryPrice the average entry price
 * @param breakEvenPrice the price at which closing makes nothing, the entry price while fees are zero
 * @param markPrice the mark price the figures are at
 * @param unRealizedProfit the profit or loss at the mark
 * @param liquidationPrice where the position would be liquidated, zero when no price can
 * @param isolatedMargin an isolated position's wallet plus its unrealized profit; zero for cross
 * @param notional the size times the mark, negative for a short
 * @param marginAsset USDT
 * @param isolatedWallet an isolated position's wallet; zero for cross
 * @param initialMargin the position's and its pair's open orders' initial margin
 * @param maintMargin the position's maintenance margin by its bracket
 * @param positionInitialMargin the position's initial margin at the mark, {@code |notional| / leverage}
 * @param openOrderInitialMargin the margin of the pair's open orders
 * @param adl always 0: auto-deleveraging is not simulated
 * @param bidNotional the value of the pair's open BUY orders that would open or increase a position
 * @param askNotional the value of the pair's open SELL orders that would
 * @param marginType CROSSED or ISOLATED
 * @param leverage the leverage
 * @param marginRatio maintenance over margin balance, of the position when isolated, of the cross side otherwise;
 *     {@code null} when the margin balance is zero or below
 * @param updateTime when the position last changed
 */
public record FuturesPositionRiskResponse(
        String symbol,
        PositionSide positionSide,
        BigDecimal positionAmt,
        BigDecimal entryPrice,
        BigDecimal breakEvenPrice,
        BigDecimal markPrice,
        BigDecimal unRealizedProfit,
        BigDecimal liquidationPrice,
        BigDecimal isolatedMargin,
        BigDecimal notional,
        String marginAsset,
        BigDecimal isolatedWallet,
        BigDecimal initialMargin,
        BigDecimal maintMargin,
        BigDecimal positionInitialMargin,
        BigDecimal openOrderInitialMargin,
        int adl,
        BigDecimal bidNotional,
        BigDecimal askNotional,
        MarginType marginType,
        int leverage,
        BigDecimal marginRatio,
        Instant updateTime) {}
