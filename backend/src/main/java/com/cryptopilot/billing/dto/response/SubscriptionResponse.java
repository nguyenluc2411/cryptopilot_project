package com.cryptopilot.billing.dto.response;

import com.cryptopilot.billing.model.enums.PlanTier;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The caller's plan (SCR-07, SCR-30): the tier in effect and, on a paid plan, the subscription that gives it.
 *
 * @param tier the plan in effect now; FREE without a running subscription
 * @param packageCode {@code null} on FREE, as are the other subscription fields
 * @param carriedValueVnd what the subscription carried over from the one it replaced
 * @param bonusDays the days that carried value bought
 */
public record SubscriptionResponse(
        PlanTier tier,
        String packageCode,
        String packageName,
        Instant startAt,
        Instant endAt,
        BigDecimal carriedValueVnd,
        Integer bonusDays) {}
