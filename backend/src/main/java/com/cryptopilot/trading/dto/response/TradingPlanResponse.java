package com.cryptopilot.trading.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.MarginMode;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A plan in full (SCR-18): its values, the stored snapshot, its warnings and its status history.
 *
 * <p>Rule: BR-31, BR-32; SRS 3.5.2.
 *
 * @param id the plan
 * @param pairId the pair
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param entryType LIMIT or MARKET
 * @param entryPrice the entry price the snapshot was calculated with
 * @param stopLoss the stop loss
 * @param takeProfit the take profit
 * @param capital the capital
 * @param riskPercent the risk %
 * @param leverage the leverage; {@code null} on Spot
 * @param marginMode ISOLATED on Futures; {@code null} on Spot
 * @param expiresAt when an unfilled ACTIVE plan expires, or {@code null}
 * @param note the Trader's note
 * @param status the status
 * @param editable whether the plan may still be edited (DRAFT)
 * @param snapshot the stored calculation
 * @param warnings the stored warnings, most severe first
 * @param blocksActivation whether a stored warning would refuse activation (MSG18)
 * @param statusHistory the statuses reached, oldest first
 */
public record TradingPlanResponse(
        UUID id,
        UUID pairId,
        MarketType market,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        BigDecimal capital,
        BigDecimal riskPercent,
        Integer leverage,
        MarginMode marginMode,
        Instant expiresAt,
        String note,
        PlanStatus status,
        boolean editable,
        PlanSnapshotResponse snapshot,
        List<PlanWarningResponse> warnings,
        boolean blocksActivation,
        List<StatusChangeResponse> statusHistory) {}
