package com.cryptopilot.billing;

import com.cryptopilot.billing.model.enums.Feature;
import java.util.UUID;

/**
 * What the {@code billing} module tells other modules about a Trader's plan (UC-53). It is the whole of the surface:
 * no other module reads the package, order or subscription tables.
 *
 * <p>The plan is decided at the moment of the call from the subscription's own start and end, never from what an
 * expiry job has or has not done yet, so access changes exactly at {@code end_at} (SRS 3.10.4). A refusal is a
 * {@link com.cryptopilot.common.exception.BusinessException}: {@code PLAN_FEATURE_NOT_INCLUDED} (MSG29) with the
 * lowest tier that includes the feature, {@code PLAN_LIMIT_REACHED} (MSG27) with the maximum, what it counts and the
 * current tier, or {@code AI_DAILY_QUOTA_EXHAUSTED} (MSG30) with the quota.
 *
 * <p>Rule: BR-62, BR-50, BR-15, BR-17; SRS v1.1 UC-53, CR-08, SRS 3.10.4; D-54, D-58, D-59, D-60, D-61.
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Service
 * Layer" (one boundary through which every caller reaches the rule).
 */
public interface EntitlementApi {

    /** The tier and entitlements the Trader has now: those of the ACTIVE subscription, else FREE. */
    EffectivePlan effectivePlan(UUID userId);

    /** Whether the Trader's plan includes the feature: a switch that is on, or a limit that is unlimited or above 0. */
    boolean hasFeature(UUID userId, Feature feature);

    /**
     * The limit the Trader's plan sets for a limited feature, {@code null} meaning unlimited.
     *
     * @throws IllegalArgumentException for a switch
     */
    Integer getLimit(UUID userId, Feature feature);

    /**
     * Refuses with MSG29 when the Trader's plan does not include the feature.
     *
     * @throws com.cryptopilot.common.exception.BusinessException {@code PLAN_FEATURE_NOT_INCLUDED}
     */
    void requireFeature(UUID userId, Feature feature);

    /**
     * Refuses with MSG27 when the Trader already holds as many items as the plan allows, i.e. {@code currentCount}
     * is at or above the maximum; an unlimited plan always passes.
     *
     * @param currentCount how many the Trader holds before the action, counted by the calling module
     * @throws com.cryptopilot.common.exception.BusinessException {@code PLAN_LIMIT_REACHED}
     * @throws IllegalArgumentException for a switch, for {@link Feature#AI_CHAT_DAILY} (see {@link #checkDailyQuota})
     *     or for a negative count
     */
    void requireWithinLimit(UUID userId, Feature feature, long currentCount);

    /**
     * Refuses the next question of the day: MSG29 when the plan has no AI Assistant (quota 0), MSG30 when
     * {@code usedToday} has reached the quota (BR-50). The calling module counts the questions of the day — completed
     * or pending, never failed, since 00:00 UTC+7 — so no quota is stored here (D-61).
     *
     * @param feature {@link Feature#AI_CHAT_DAILY}, the only daily quota
     * @param usedToday the questions that count against today's quota
     * @throws com.cryptopilot.common.exception.BusinessException {@code PLAN_FEATURE_NOT_INCLUDED} or
     *     {@code AI_DAILY_QUOTA_EXHAUSTED}
     */
    void checkDailyQuota(UUID userId, Feature feature, long usedToday);
}
