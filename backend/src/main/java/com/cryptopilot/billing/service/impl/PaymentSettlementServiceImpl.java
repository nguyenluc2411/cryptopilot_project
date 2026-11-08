package com.cryptopilot.billing.service.impl;

import com.cryptopilot.billing.calculator.CarryOverCalculator;
import com.cryptopilot.billing.calculator.OrderTypeResolver;
import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.entity.UserSubscription;
import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.PackageTerms;
import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.SubscriptionTerms;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.repository.UserSubscriptionRepository;
import com.cryptopilot.billing.service.PaymentSettlementService;
import com.cryptopilot.common.lock.UserLock;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles an order from a gateway report (BR-53, NSF-12) and activates what it bought (D-146).
 *
 * <p>The order row is locked first, so a repeated IPN waits and then finds the order settled; then the Trader's
 * billing lock, so the subscription is never changed by two payments at once. A confirmed payment must be for the
 * order's amount; one for another amount fails a PENDING order and activates nothing. A payment reported after the
 * order expired is still honoured (A-03).
 *
 * <p>The subscription is worked out again from the one held when paid, never from the quote: an ACTIVE row past its
 * end is expired first; the same tier renews, a higher one upgrades with the unused value carried over, and a lower
 * one (the Trader upgraded through another order meanwhile) leaves the subscriptions alone and flags the paid order
 * for an administrator.
 *
 * <p>Rule: BR-53, BR-55, BR-56, BR-63 as amended by D-146; NSF-12; A-03.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaymentSettlementServiceImpl implements PaymentSettlementService {

    /** The response code kept on an order paid with another amount. */
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementServiceImpl.class);

    private final SubscriptionOrderRepository orders;
    private final UserSubscriptionRepository subscriptions;
    private final SubscriptionPackageRepository packages;
    private final UserLock userLock;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public void settle(PaymentResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Optional<SubscriptionOrder> found = orders.lockByOrderCode(result.orderCode());
        if (found.isEmpty()) {
            log.warn("{} reported order {}, which does not exist", result.gateway(), result.orderCode());
            return;
        }
        SubscriptionOrder order = found.get();
        if (order.getPaymentGateway() != result.gateway()) {
            log.warn(
                    "{} reported order {}, which is paid through {}",
                    result.gateway(),
                    order.getOrderCode(),
                    order.getPaymentGateway());
            return;
        }
        if (order.getOrderStatus() == OrderStatus.PAID || order.getOrderStatus() == OrderStatus.FAILED) {
            log.info(
                    "order {} is already {}; the report changes nothing", order.getOrderCode(), order.getOrderStatus());
            return;
        }
        Instant now = clock.instant();
        if (!result.paid()) {
            if (order.getOrderStatus() == OrderStatus.PENDING) {
                order.markFailed(result.responseCode(), result.payload(), now);
            }
            return;
        }
        if (result.amount().compareTo(order.getAmount()) != 0) {
            log.warn(
                    "order {} charges {} but {} reported {} paid; nothing is activated",
                    order.getOrderCode(),
                    order.getAmount(),
                    result.gateway(),
                    result.amount());
            if (order.getOrderStatus() == OrderStatus.PENDING) {
                order.markFailed(AMOUNT_MISMATCH, result.payload(), now);
            }
            return;
        }
        userLock.lock(BillingServiceImpl.LOCK_SCOPE, order.getUserId());
        order.markPaid(result.transactionNo(), result.responseCode(), result.payload(), now);
        activate(order, now);
    }

    private void activate(SubscriptionOrder order, Instant now) {
        SubscriptionPackage bought = packages.findById(order.getPackageId()).orElseThrow();
        UserSubscription held = subscriptions
                .findByUserIdAndStatus(order.getUserId(), SubscriptionStatus.ACTIVE)
                .orElse(null);
        if (held != null && !held.getEndAt().isAfter(now)) {
            held.expire(now);
            subscriptions.flush();
            held = null;
        }
        HeldSubscription current = held == null ? null : held.held(tierOf(held));
        if (OrderTypeResolver.resolve(current, bought.getTier(), now).isEmpty()) {
            log.warn(
                    "order {} for {} was paid after the Trader moved to a higher tier; flagged for an administrator",
                    order.getOrderCode(),
                    bought.getPackageCode());
            order.flagForManualHandling();
            return;
        }
        SubscriptionTerms terms = CarryOverCalculator.terms(
                current, new PackageTerms(bought.getTier(), order.getAmount(), bought.getDurationDays()), now);
        if (held != null) {
            held.replace();
            subscriptions.flush();
        }
        subscriptions.save(UserSubscription.open(order, terms, held));
        events.publishEvent(new SubscriptionOrderPaid(
                order.getId(),
                order.getUserId(),
                order.getOrderCode(),
                bought.getPackageName(),
                terms.orderType().name(),
                order.getAmount(),
                order.getCurrency(),
                order.getPaymentGateway().name(),
                order.getGatewayTransactionNo(),
                now,
                terms.startAt(),
                terms.endAt(),
                terms.carriedValueVnd(),
                terms.bonusDays()));
    }

    private PlanTier tierOf(UserSubscription subscription) {
        SubscriptionOrder order = orders.findById(subscription.getOrderId()).orElseThrow();
        return packages.findById(order.getPackageId()).orElseThrow().getTier();
    }
}
