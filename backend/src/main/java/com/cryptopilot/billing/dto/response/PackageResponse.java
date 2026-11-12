package com.cryptopilot.billing.dto.response;

import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;

/**
 * A package of SCR-30: FREE for comparison and every paid package on sale, with the entitlements of its tier.
 *
 * @param durationDays {@code null} for FREE
 * @param purchasable false for FREE
 * @param activePlanMax {@code null} is unlimited, as are the other limits
 */
public record PackageResponse(
        String code,
        String name,
        PlanTier tier,
        BigDecimal price,
        String currency,
        Integer durationDays,
        boolean purchasable,
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
