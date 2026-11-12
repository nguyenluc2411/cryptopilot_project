package com.cryptopilot.billing.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.billing.calculator.CarryOverCalculator;
import com.cryptopilot.billing.calculator.OrderTypeResolver;
import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.entity.UserSubscription;
import com.cryptopilot.billing.event.PlanDowngraded;
import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.PackageTerms;
import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.SubscriptionTerms;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.repository.UserSubscriptionRepository;
import com.cryptopilot.billing.service.PaymentSettlementService;
import com.cryptopilot.common.lock.UserLock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles an order from a gateway report (BR-53, NSF-12) and activates what it bought (D-147).
 *
 * <p>The order row is locked first ({@code FOR UPDATE}), so a repeated IPN, or an IPN and a reconciliation of the same
 * payment, wait for each other and the second finds the order settled; then the Trader's billing lock, so the
 * subscription is never changed by two payments at once. Only a PENDING order is paid (STM-06).
 *
 * <p>Refused and audited, the order unchanged (NSF-12): no such order, another gateway, another amount. A payment for
 * an order already EXPIRED or FAILED keeps that status, is stored, flagged for an administrator and audited; nothing is
 * activated while A-03 is open.
 *
 * <p>The subscription is worked out again from the one held when paid, never from the quote: an ACTIVE row past its
 * end is expired first; the same tier renews, a higher one upgrades with the unused value carried over, and a lower
 * one (the Trader upgraded through another order meanwhile) is turned into days of the plan held, so no paid order
 * needs an administrator.
 *
 * <p>Rule: BR-53, BR-55, BR-56, BR-63 as amended by D-147; NSF-12; STM-06; TECHNICAL_DESIGN 5.5.
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 11 (exactly-once
 * effect through idempotent processing); Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>.
 * Addison-Wesley (Pessimistic Offline Lock).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PaymentSettlementServiceImpl implements PaymentSettlementService {

    static final String UNKNOWN_ORDER = "UNKNOWN_ORDER";
    static final String GATEWAY_MISMATCH = "GATEWAY_MISMATCH";
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementServiceImpl.class);

    private final SubscriptionOrderRepository orders;
    private final UserSubscriptionRepository subscriptions;
    private final SubscriptionPackageRepository packages;
    private final UserLock userLock;
    private final AdminApi admin;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public void settle(PaymentResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Optional<SubscriptionOrder> found = orders.lockByOrderCode(result.orderCode());
        if (found.isEmpty()) {
            log.warn("{} reported order {}, which does not exist", result.gateway(), result.orderCode());
            audit(AuditAction.IPN_REJECTED, null, rejection(result, UNKNOWN_ORDER));
            return;
        }
        SubscriptionOrder order = found.get();
        if (order.getPaymentGateway() != result.gateway()) {
            log.warn(
                    "{} reported order {}, which is paid through {}",
                    result.gateway(),
                    order.getOrderCode(),
                    order.getPaymentGateway());
            audit(AuditAction.IPN_REJECTED, order.getId(), rejection(result, GATEWAY_MISMATCH));
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
                    "order {} charges {} but {} reported {} paid; refused, the order is unchanged",
                    order.getOrderCode(),
                    order.getAmount(),
                    result.gateway(),
                    result.amount());
            Map<String, Object> values = rejection(result, AMOUNT_MISMATCH);
            values.put("orderAmount", order.getAmount());
            values.put("reportedAmount", result.amount());
            audit(AuditAction.IPN_REJECTED, order.getId(), values);
            return;
        }
        switch (order.getOrderStatus()) {
            case PAID -> log.info("order {} is already PAID; the report changes nothing", order.getOrderCode());
            case EXPIRED, FAILED -> late(order, result, now);
            case PENDING -> {
                userLock.lock(BillingServiceImpl.LOCK_SCOPE, order.getUserId());
                order.markPaid(result.transactionNo(), result.responseCode(), result.payload(), now);
                activate(order, now);
            }
        }
    }

    @Override
    @Transactional
    public boolean expireIfDue(String orderCode) {
        Optional<SubscriptionOrder> found = orders.lockByOrderCode(orderCode);
        Instant now = clock.instant();
        if (found.isEmpty()
                || found.get().getOrderStatus() != OrderStatus.PENDING
                || now.isBefore(found.get().getExpiresAt())) {
            return false;
        }
        found.get().expire(now);
        log.info("order {} expired unpaid", orderCode);
        return true;
    }

    @Override
    @Transactional
    public boolean withdraw(String orderCode) {
        Optional<SubscriptionOrder> found = orders.lockByOrderCode(orderCode);
        if (found.isEmpty() || found.get().getOrderStatus() != OrderStatus.PENDING) {
            return false;
        }
        found.get().withdraw(clock.instant());
        log.info("order {} withdrawn for a newer order", orderCode);
        return true;
    }

    @Override
    @Transactional
    public void auditRejected(GatewayCode gateway, String reason) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gateway", gateway.name());
        values.put("reason", reason);
        audit(AuditAction.IPN_REJECTED, null, values);
    }

    private void late(SubscriptionOrder order, PaymentResult result, Instant now) {
        OrderStatus status = order.getOrderStatus();
        if (!order.recordLatePayment(result.transactionNo(), result.responseCode(), result.payload(), now)) {
            log.info("the late payment of order {} was recorded already", order.getOrderCode());
            return;
        }
        log.warn(
                "{} reported a payment for order {}, which is {}; kept for an administrator",
                result.gateway(),
                order.getOrderCode(),
                status);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("orderCode", order.getOrderCode());
        values.put("status", status.name());
        values.put("gateway", result.gateway().name());
        values.put("transactionNo", result.transactionNo());
        values.put("amount", result.amount());
        values.put("manualHandling", true);
        audit(AuditAction.LATE_PAYMENT, order.getId(), values);
    }

    private void audit(AuditAction action, UUID orderId, Map<String, Object> values) {
        admin.audit(new AuditEntry(null, action, AuditedEntity.SUBSCRIPTION_ORDER, orderId, null, values));
    }

    private static Map<String, Object> rejection(PaymentResult result, String reason) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gateway", result.gateway().name());
        values.put("orderCode", result.orderCode());
        values.put("reason", reason);
        return values;
    }

    private void activate(SubscriptionOrder order, Instant now) {
        SubscriptionPackage bought = packages.findById(order.getPackageId()).orElseThrow();
        UserSubscription held = subscriptions
                .findByUserIdAndStatus(order.getUserId(), SubscriptionStatus.ACTIVE)
                .orElse(null);
        if (held != null && !held.getEndAt().isAfter(now)) {
            PlanTier ended = tierOf(held);
            held.expire(now);
            subscriptions.flush();
            held = null;
            if (ended.rank() > bought.getTier().rank()) {
                // The plan that ran out ranked higher than the one bought now: BR-64 applies as after any drop.
                events.publishEvent(new PlanDowngraded(
                        order.getUserId(), ended.name(), bought.getTier().name(), bought.getPackageName(), now));
            }
        }
        HeldSubscription current = held == null ? null : held.held(tierOf(held));
        if (OrderTypeResolver.resolve(current, bought.getTier(), now).isEmpty()) {
            convert(order, held, current, now);
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

    /**
     * A payment for a lower plan than the one held (the Trader moved up through another order meanwhile) buys days of
     * the plan held, at the price the Trader paid for it: nobody loses money and no administrator steps in (D-147).
     */
    private void convert(SubscriptionOrder order, UserSubscription held, HeldSubscription current, Instant now) {
        SubscriptionPackage heldPackage = packageOf(held);
        int days = CarryOverCalculator.convertedDays(
                order.getAmount(),
                current,
                new PackageTerms(heldPackage.getTier(), heldPackage.getPriceAmount(), heldPackage.getDurationDays()));
        held.extend(order.getAmount(), days);
        log.info(
                "order {} paid for a lower plan than {} holds; turned into {} more days of {}",
                order.getOrderCode(),
                order.getUserId(),
                days,
                heldPackage.getPackageCode());
        events.publishEvent(new SubscriptionOrderPaid(
                order.getId(),
                order.getUserId(),
                order.getOrderCode(),
                heldPackage.getPackageName(),
                order.getOrderType().name(),
                order.getAmount(),
                order.getCurrency(),
                order.getPaymentGateway().name(),
                order.getGatewayTransactionNo(),
                now,
                held.getStartAt(),
                held.getEndAt(),
                BigDecimal.ZERO,
                days));
    }

    private SubscriptionPackage packageOf(UserSubscription subscription) {
        SubscriptionOrder order = orders.findById(subscription.getOrderId()).orElseThrow();
        return packages.findById(order.getPackageId()).orElseThrow();
    }

    private PlanTier tierOf(UserSubscription subscription) {
        SubscriptionOrder order = orders.findById(subscription.getOrderId()).orElseThrow();
        return packages.findById(order.getPackageId()).orElseThrow().getTier();
    }
}
