package com.cryptopilot.billing.service.impl;

import com.cryptopilot.billing.EffectivePlan;
import com.cryptopilot.billing.Feature;
import com.cryptopilot.billing.PlanTier;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.service.EntitlementService;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entitlement checks of UC-53, read from the database at every call: the effective plan is the package of the
 * Trader's ACTIVE subscription running now, else the single FREE package. Nothing is cached, so a subscription that
 * starts, ends or is replaced changes the answer on the next call.
 *
 * <p>Two ACTIVE subscriptions running at once should not exist (BR-55 keeps one); if the data holds them anyway the
 * highest tier wins and a warning is logged. A missing FREE package — a deployment without the package seed of
 * T-007 — and a feature no active package includes are configuration errors and fail loudly rather than guessing.
 *
 * <p>Rule: BR-62, BR-50, BR-15, BR-17; SRS v1.1 UC-53, CR-08, SRS 3.10.4; D-54, D-58, D-59, D-60, D-61.
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Service
 * Layer".
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntitlementServiceImpl implements EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementServiceImpl.class);

    private final SubscriptionPackageRepository packages;
    private final Clock clock;

    @Override
    public EffectivePlan effectivePlan(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        List<SubscriptionPackage> held = packages.findHeldAt(userId, clock.instant());
        if (held.size() > 1) {
            log.warn(
                    "User {} holds {} ACTIVE subscriptions at once; the highest tier, {}, applies",
                    userId,
                    held.size(),
                    held.getFirst().getTier());
        }
        SubscriptionPackage plan = held.isEmpty() ? freePackage() : held.getFirst();
        return new EffectivePlan(plan.getTier(), plan.entitlements());
    }

    @Override
    public boolean hasFeature(UUID userId, Feature feature) {
        return feature.includedIn(effectivePlan(userId).entitlements());
    }

    @Override
    public Integer getLimit(UUID userId, Feature feature) {
        requireLimit(feature);
        return feature.limitIn(effectivePlan(userId).entitlements());
    }

    @Override
    public void requireFeature(UUID userId, Feature feature) {
        EffectivePlan plan = effectivePlan(userId);
        if (!feature.includedIn(plan.entitlements())) {
            throw notIncluded(feature, plan);
        }
    }

    @Override
    public void requireWithinLimit(UUID userId, Feature feature, long currentCount) {
        requireLimit(feature);
        if (feature == Feature.AI_CHAT_DAILY) {
            throw new IllegalArgumentException("the daily AI quota is checked with checkDailyQuota");
        }
        requireCount(currentCount);
        EffectivePlan plan = effectivePlan(userId);
        Integer max = feature.limitIn(plan.entitlements());
        if (max != null && currentCount >= max) {
            throw new BusinessException(
                    ErrorCode.PLAN_LIMIT_REACHED,
                    plan.tier() + " allows " + max + " " + feature.items() + " and " + currentCount + " are held",
                    max,
                    feature.items(),
                    plan.tier());
        }
    }

    @Override
    public void checkDailyQuota(UUID userId, Feature feature, long usedToday) {
        if (feature != Feature.AI_CHAT_DAILY) {
            throw new IllegalArgumentException(feature + " is not a daily quota");
        }
        requireCount(usedToday);
        EffectivePlan plan = effectivePlan(userId);
        int quota = feature.limitIn(plan.entitlements());
        if (quota == 0) {
            throw notIncluded(feature, plan);
        }
        if (usedToday >= quota) {
            throw new BusinessException(
                    ErrorCode.AI_DAILY_QUOTA_EXHAUSTED,
                    plan.tier() + " allows " + quota + " AI questions a day and " + usedToday + " were asked today",
                    quota);
        }
    }

    private SubscriptionPackage freePackage() {
        List<SubscriptionPackage> free = packages.findByTier(PlanTier.FREE);
        if (free.isEmpty()) {
            throw new IllegalStateException("no FREE package is stored; the package seed (T-007) has not run");
        }
        return free.getFirst();
    }

    /** MSG29 names the lowest tier with an active package that includes the feature: a tier the Trader can still get. */
    private BusinessException notIncluded(Feature feature, EffectivePlan plan) {
        PlanTier lowest = packages.findAllByActiveTrueOrderByTierRankAsc().stream()
                .filter(p -> feature.includedIn(p.entitlements()))
                .map(SubscriptionPackage::getTier)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no active package includes " + feature));
        return new BusinessException(
                ErrorCode.PLAN_FEATURE_NOT_INCLUDED,
                plan.tier() + " does not include " + feature + "; " + lowest + " does",
                lowest);
    }

    private static void requireLimit(Feature feature) {
        if (feature.kind() != Feature.Kind.LIMIT) {
            throw new IllegalArgumentException(feature + " is a switch, not a limit");
        }
    }

    private static void requireCount(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("a count is not negative, was " + count);
        }
    }
}
