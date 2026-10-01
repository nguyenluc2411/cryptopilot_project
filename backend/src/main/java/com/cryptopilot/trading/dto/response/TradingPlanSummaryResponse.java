package com.cryptopilot.trading.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.PlanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of the plan list (SCR-16): the key figures, without the warnings.
 *
 * <p>Rule: SRS 3.5.2; CR-04.
 *
 * @param id the plan
 * @param pairId the pair
 * @param market SPOT or FUTURES
 * @param direction LONG or SHORT
 * @param entryType LIMIT or MARKET
 * @param entryPrice the entry price
 * @param stopLoss the stop loss
 * @param takeProfit the take profit
 * @param leverage the leverage; {@code null} on Spot
 * @param riskAmount the loss at the stop loss
 * @param riskRewardRatio reward / risk
 * @param status the status
 * @param createdAt when the plan was created
 * @param activatedAt when it was activated, or {@code null}
 * @param expiresAt when an unfilled ACTIVE plan expires, or {@code null}
 */
public record TradingPlanSummaryResponse(
        UUID id,
        UUID pairId,
        MarketType market,
        Direction direction,
        EntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit,
        Integer leverage,
        BigDecimal riskAmount,
        BigDecimal riskRewardRatio,
        PlanStatus status,
        Instant createdAt,
        Instant activatedAt,
        Instant expiresAt) {}
