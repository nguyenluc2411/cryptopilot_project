package com.cryptopilot.trading.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What closing an open position now would give, for the Close Position pop-up (SCR-19, MSG21). Nothing is stored or
 * reserved: the close executes at the price of its own moment, and equals this estimate when the price has not moved.
 *
 * <p>Rule: UC-21, BR-35, BR-36, BR-37, BR-38, BR-39; D-97; D-111 E5.
 *
 * @param currentPrice the current last price; on Futures the last price, never the mark price
 * @param estimatedExitPrice the current price moved by {@code SLIPPAGE_BPS} against the Trader, on the tick: the price
 *     of a taker sell for a LONG, of a taker buy for a SHORT
 * @param priceResult the price result at the estimated exit price, without fees or funding (BR-35)
 * @param entryFee the fee stored at the entry fill
 * @param estimatedExitFee the taker fee at the estimated exit price (BR-36)
 * @param fundingSoFar the signed funding settled since the fill, received positive; {@code null} on Spot (BR-37)
 * @param fundingProvisional whether a funding settlement may still be missing from {@code fundingSoFar}
 * @param estimatedNetPnl {@code priceResult − entryFee − estimatedExitFee + fundingSoFar} (BR-38)
 * @param at when the estimate was computed
 */
public record CloseEstimateResponse(
        BigDecimal currentPrice,
        BigDecimal estimatedExitPrice,
        BigDecimal priceResult,
        BigDecimal entryFee,
        BigDecimal estimatedExitFee,
        BigDecimal fundingSoFar,
        boolean fundingProvisional,
        BigDecimal estimatedNetPnl,
        Instant at) {}
