package com.cryptopilot.paper.dto.response;

import java.time.Instant;

/**
 * The Trader's Futures account settings. Binance: {@code GET /fapi/v1/accountConfig}.
 *
 * @param feeTier always 0: commission is configured separately
 * @param canTrade false while a cross liquidation runs
 * @param canDeposit whether USDT can be moved in from the Spot wallet
 * @param canWithdraw false while a cross liquidation runs
 * @param dualSidePosition true in hedge mode, false in one-way mode
 * @param multiAssetsMargin always false: only USDT is margin
 * @param updateTime when the settings last changed
 */
public record FuturesAccountConfigResponse(
        int feeTier,
        boolean canTrade,
        boolean canDeposit,
        boolean canWithdraw,
        boolean dualSidePosition,
        boolean multiAssetsMargin,
        Instant updateTime) {}
