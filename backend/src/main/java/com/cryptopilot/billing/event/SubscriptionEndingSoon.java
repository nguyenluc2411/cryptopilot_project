package com.cryptopilot.billing.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The Trader's subscription ends within 3 days and was not renewed (NSF-14); published once per subscription.
 *
 * @param subscriptionId the subscription, also the key that keeps the reminder from being written twice
 * @param userId the Trader
 * @param packageName the display name of the package held
 * @param endAt when the subscription ends
 */
public record SubscriptionEndingSoon(UUID subscriptionId, UUID userId, String packageName, Instant endAt) {

    public SubscriptionEndingSoon {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(endAt, "endAt must not be null");
    }
}
