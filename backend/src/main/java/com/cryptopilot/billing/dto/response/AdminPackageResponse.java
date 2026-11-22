package com.cryptopilot.billing.dto.response;

import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One package as the administration console shows it (SCR-39), inactive ones included, with the limits of its tier.
 *
 * <p>Rule: SRS 3.11.5; BR-62; UC-42.
 *
 * @param id the package
 * @param code its unique code
 * @param name its name
 * @param tier FREE, PRO or PREMIUM
 * @param tierRank 0, 1 or 2
 * @param price the price in VND
 * @param currency always {@code VND}
 * @param durationDays 1–366, {@code null} for FREE
 * @param purchasable whether Traders can buy it
 * @param active whether it is on offer
 * @param hasOrders whether orders were placed for it: such a package can be deactivated, not deleted
 * @param limits the limits and features of its tier
 * @param updatedAt when it last changed
 */
public record AdminPackageResponse(
        UUID id,
        String code,
        String name,
        PlanTier tier,
        int tierRank,
        BigDecimal price,
        String currency,
        Integer durationDays,
        boolean purchasable,
        boolean active,
        boolean hasOrders,
        Limits limits,
        Instant updatedAt) {

    /** The limits and features of a tier; a {@code null} count means no limit (BR-62). */
    public record Limits(
            boolean futuresAnalysis,
            boolean scoreComponents,
            Integer activePlanMax,
            Integer watchlistMax,
            Integer activeAlertMax,
            boolean indicatorAlert,
            boolean externalAlertChannels,
            boolean advancedPerformance,
            boolean newsAiInsight,
            int aiDailyQuota,
            boolean aiPerformanceContext,
            boolean videoPost) {}
}
