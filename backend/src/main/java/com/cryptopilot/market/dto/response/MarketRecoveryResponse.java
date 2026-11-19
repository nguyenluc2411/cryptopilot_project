package com.cryptopilot.market.dto.response;

/**
 * How far a recovering market has caught up (D-158), for the banner's progress.
 *
 * @param pairsDone the pairs with orders, positions or plans that have replayed the missed data
 * @param pairsTotal the pairs with orders, positions or plans on the market
 */
public record MarketRecoveryResponse(int pairsDone, int pairsTotal) {}
