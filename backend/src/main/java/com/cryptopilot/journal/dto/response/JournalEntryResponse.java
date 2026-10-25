package com.cryptopilot.journal.dto.response;

import com.cryptopilot.journal.model.enums.TradeSource;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One trade of the journal (SCR-20), as its record holds it. The exit, its amounts and the net P/L are set once it is
 * CLOSED; they are the amounts the plan detail shows for the same position.
 *
 * <p>Rule: BR-34, BR-35, BR-36, BR-37, BR-38; UC-22; D-97 and its note of 2026-10-24.
 *
 * @param id the journal record
 * @param planId the plan the trade carried out; {@code null} for a manual trade
 * @param pairId the pair traded
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param source SIMULATED (a filled plan) or MANUAL
 * @param status OPEN or CLOSED
 * @param leverage Futures only; {@code null} on Spot
 * @param entryPrice the entry fill price
 * @param quantity the quantity held
 * @param entryTime when the entry filled
 * @param entryFee the fee paid on the entry fill; {@code null} when unknown (a manual trade)
 * @param stopLoss the current stop loss; the last one once CLOSED
 * @param takeProfit the current take profit; the last one once CLOSED
 * @param exitPrice CLOSED only: the exit fill price
 * @param exitTime CLOSED only: when the exit counts
 * @param closeReason CLOSED only: why it closed
 * @param realizedPnl CLOSED only: the price result, without fees or funding (BR-35)
 * @param feeAmount CLOSED only: the entry and exit fees (BR-36)
 * @param fundingFeeAmount CLOSED Futures only: the signed funding known so far (BR-37); {@code null} on Spot
 * @param netPnl CLOSED only: {@code realizedPnl − feeAmount + fundingFeeAmount} (BR-38)
 * @param provisional CLOSED only: whether a funding settlement may still be missing; {@code false} while OPEN
 */
public record JournalEntryResponse(
        UUID id,
        UUID planId,
        UUID pairId,
        MarketType market,
        Direction direction,
        TradeSource source,
        PositionStatus status,
        Integer leverage,
        BigDecimal entryPrice,
        BigDecimal quantity,
        Instant entryTime,
        BigDecimal entryFee,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal exitPrice,
        Instant exitTime,
        CloseReason closeReason,
        BigDecimal realizedPnl,
        BigDecimal feeAmount,
        BigDecimal fundingFeeAmount,
        BigDecimal netPnl,
        boolean provisional) {}
