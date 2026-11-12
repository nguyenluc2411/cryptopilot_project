package com.cryptopilot.billing.model;

import com.cryptopilot.billing.model.enums.OrderType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The subscription a paid order creates, as the carry-over formula works it out (D-147).
 *
 * @param orderType NEW, RENEW or UPGRADE
 * @param startAt when the order was paid
 * @param endAt the end of the new period, bonus days included
 * @param valueVnd what the new period is worth: the amount paid plus {@code carriedValueVnd}
 * @param carriedValueVnd the unused value of the subscription replaced, in whole VND; 0 for NEW
 * @param bonusDays the days of the new tier the carried value bought; only an upgrade has them
 */
public record SubscriptionTerms(
        OrderType orderType,
        Instant startAt,
        Instant endAt,
        BigDecimal valueVnd,
        BigDecimal carriedValueVnd,
        int bonusDays) {}
