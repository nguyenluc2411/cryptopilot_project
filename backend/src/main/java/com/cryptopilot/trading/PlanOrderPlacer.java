package com.cryptopilot.trading;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a plan is carried out (RF-33): the demo account's orders. The plans' module calls this port and paper trading
 * implements it, so the dependency points from paper trading to the plans and never back (D-117, one engine).
 *
 * <p>Rule: BR-33, BR-68, BR-71; D-117; RF-33.
 */
public interface PlanOrderPlacer {

    /**
     * Places the orders of a plan being activated, in the activation's transaction: a MARKET entry fills at once and
     * sets the plan EXECUTED through {@link TradingPlanApi#entryFilled}; a LIMIT or STOP entry waits in the paper book.
     *
     * @throws com.cryptopilot.common.exception.BusinessException any refusal of the order paths, such as
     *     {@code PAPER_INSUFFICIENT_BALANCE} (BR-71): the activation then rolls back and nothing is stored
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when the demo account is not opened (BR-68)
     */
    void place(PlanOrderTerms terms);

    /**
     * Cancels the orders of a plan that ends before its entry filled: cancelled by the Trader, expired or invalidated
     * (BR-68). Orders already done are left as they are. Joins the caller's transaction.
     */
    void cancel(UUID userId, UUID planId);

    /**
     * What closing the plan's OPEN position at the market would bring now (UC-21): a MARKET sale of the coin it holds
     * on Spot, a MARKET reduce of the whole position on Futures, both priced at the current last price.
     *
     * @param trade the position as the journal holds it
     * @throws com.cryptopilot.common.exception.BusinessException {@code MARKET_PRICE_UNAVAILABLE} without a current
     *     last price
     */
    PlanExitQuote quoteClose(UUID userId, OpenTrade trade);

    /**
     * Closes the plan's OPEN position at the market (UC-21): its take profit and stop loss orders are cancelled, then
     * a MARKET order sells or reduces what is held. The fill closes the journal record (RF-35). Joins the caller's
     * transaction.
     */
    void close(UUID userId, OpenTrade trade);

    /**
     * Replaces the take profit and stop loss orders of the plan's OPEN position with the given levels (D-124); either
     * may be {@code null} for none. Joins the caller's transaction.
     */
    void replaceLevels(UUID userId, OpenTrade trade, BigDecimal stopLoss, BigDecimal takeProfit);

    /** The current liquidation price of the plan's OPEN Futures position on the demo account, if it has one. */
    Optional<BigDecimal> liquidationPrice(UUID userId, UUID planId);

    /**
     * A close at the market, priced now.
     *
     * @param currentPrice the current last price
     * @param exitPrice the price the MARKET order is expected to fill at
     * @param quantity what it sells or reduces
     * @param exitFee the taker fee of it, in the quote asset
     * @param fundingSoFar the funding the Futures position has settled (negative when paid), {@code null} on Spot
     */
    record PlanExitQuote(
            BigDecimal currentPrice,
            BigDecimal exitPrice,
            BigDecimal quantity,
            BigDecimal exitFee,
            BigDecimal fundingSoFar) {}
}
