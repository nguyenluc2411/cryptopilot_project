package com.cryptopilot.billing.service.impl;

import com.cryptopilot.billing.config.SubscriptionSweepProperties;
import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.entity.UserSubscription;
import com.cryptopilot.billing.event.PlanDowngraded;
import com.cryptopilot.billing.event.SubscriptionEndingSoon;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.repository.UserSubscriptionRepository;
import com.cryptopilot.billing.service.SubscriptionLifecycleService;
import com.cryptopilot.common.lock.UserLock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires and reminds subscriptions, one transaction each, under the Trader's billing lock: the lock a payment takes
 * too, so a payment settling at the same moment either finds the subscription expired already or is waited for, and
 * the run then finds it REPLACED and leaves it alone.
 *
 * <p>Rule: NSF-14; BR-64; D-147.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SubscriptionLifecycleServiceImpl implements SubscriptionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleServiceImpl.class);

    private final UserSubscriptionRepository subscriptions;
    private final SubscriptionOrderRepository orders;
    private final SubscriptionPackageRepository packages;
    private final UserLock userLock;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final SubscriptionSweepProperties properties;
    private final Clock clock;

    @Override
    public int expireEnded() {
        Instant now = clock.instant();
        return each(subscriptions.findEndedIds(now, page()), this::expire);
    }

    @Override
    public int remindEnding() {
        Instant now = clock.instant();
        return each(subscriptions.findIdsToRemind(now, now.plus(properties.remindBefore()), page()), this::remind);
    }

    private boolean expire(UserSubscription subscription) {
        Instant now = clock.instant();
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE || now.isBefore(subscription.getEndAt())) {
            return false;
        }
        SubscriptionPackage held = packageOf(subscription);
        subscription.expire(now);
        SubscriptionPackage free = packages.findByTier(PlanTier.FREE).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no FREE package is seeded"));
        events.publishEvent(new PlanDowngraded(
                subscription.getUserId(), held.getTier().name(), PlanTier.FREE.name(), free.getPackageName(), now));
        log.info("subscription {} of {} ended; the Trader is on FREE", subscription.getId(), subscription.getUserId());
        return true;
    }

    private boolean remind(UserSubscription subscription) {
        Instant now = clock.instant();
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                || !subscription.getEndAt().isAfter(now)
                || !subscription.remind(now)) {
            return false;
        }
        events.publishEvent(new SubscriptionEndingSoon(
                subscription.getId(),
                subscription.getUserId(),
                packageOf(subscription).getPackageName(),
                subscription.getEndAt()));
        return true;
    }

    /** Applies {@code step} to each subscription in a transaction of its own; returns how many it changed. */
    private int each(List<UUID> ids, Predicate<UserSubscription> step) {
        int changed = 0;
        for (UUID id : ids) {
            try {
                Boolean done = transactions.execute(status -> {
                    UUID owner = subscriptions.findUserIdOf(id).orElse(null);
                    if (owner == null) {
                        return false;
                    }
                    userLock.lock(BillingServiceImpl.LOCK_SCOPE, owner);
                    // Read under the lock: a payment may have replaced it while the lock was awaited.
                    return subscriptions.findById(id).map(step::test).orElse(false);
                });
                if (Boolean.TRUE.equals(done)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.error("subscription {} could not be handled; the next run retries", id, e);
            }
        }
        return changed;
    }

    private SubscriptionPackage packageOf(UserSubscription subscription) {
        SubscriptionOrder order = orders.findById(subscription.getOrderId()).orElseThrow();
        return packages.findById(order.getPackageId()).orElseThrow();
    }

    private PageRequest page() {
        return PageRequest.of(0, properties.batchSize());
    }
}
