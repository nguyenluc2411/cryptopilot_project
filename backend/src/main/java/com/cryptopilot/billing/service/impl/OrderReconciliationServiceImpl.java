package com.cryptopilot.billing.service.impl;

import com.cryptopilot.billing.client.GatewayUnavailableException;
import com.cryptopilot.billing.client.PaymentGateway;
import com.cryptopilot.billing.config.ReconcileProperties;
import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.model.GatewayLookup;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.service.OrderReconciliationService;
import com.cryptopilot.billing.service.PaymentSettlementService;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Reconciles orders with their gateway (NSF-13). The gateway is asked outside any transaction; what it answers is then
 * applied by the {@link PaymentSettlementService}, which locks the order, so a reconciliation and an IPN for the same
 * payment settle it once between them (BR-53).
 *
 * <p>Only a confirmed payment settles anything. An unpaid order is cancelled at its gateway, then expires when its
 * window has closed (or is withdrawn for a newer order); it is never failed here, so a payment that still arrives
 * later is honoured (A-03). A gateway this deployment cannot ask (not configured) lets its orders expire when due.
 *
 * <p>Rule: BR-53, BR-54; NSF-13; A-03; D-146.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class OrderReconciliationServiceImpl implements OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationServiceImpl.class);

    /** How long past its window an order the gateway will not cancel is still left PENDING. */
    static final Duration CANCEL_GRACE = Duration.ofDays(1);

    private final SubscriptionOrderRepository orders;
    private final ObjectProvider<PaymentGateway> gateways;
    private final PaymentSettlementService settlement;
    private final ReconcileProperties properties;
    private final Clock clock;

    @Override
    public OrderStatus reconcile(String orderCode) {
        SubscriptionOrder order =
                find(orderCode).orElseThrow(() -> new ResourceNotFoundException("Subscription order", orderCode));
        if (order.getOrderStatus() == OrderStatus.PAID || order.getOrderStatus() == OrderStatus.FAILED) {
            return order.getOrderStatus();
        }
        try {
            apply(order);
        } catch (GatewayUnavailableException e) {
            throw new BusinessException(
                    ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    order.getPaymentGateway() + " could not be asked about " + orderCode + ": " + e.getMessage(),
                    e);
        }
        return find(orderCode).orElseThrow().getOrderStatus();
    }

    @Override
    public int reconcileDue() {
        List<String> due = orders.findCodesCreatedBefore(
                OrderStatus.PENDING,
                clock.instant().minus(properties.after()),
                PageRequest.of(0, properties.batchSize()));
        int changed = 0;
        for (String code : due) {
            try {
                Optional<SubscriptionOrder> order = find(code);
                if (order.isPresent() && order.get().getOrderStatus() == OrderStatus.PENDING) {
                    apply(order.get());
                    if (find(code).orElseThrow().getOrderStatus() != OrderStatus.PENDING) {
                        changed++;
                    }
                }
            } catch (GatewayUnavailableException e) {
                log.warn("order {} stays PENDING, its gateway could not be asked: {}", code, e.getMessage());
            } catch (RuntimeException e) {
                log.error("order {} could not be reconciled; the next run retries", code, e);
            }
        }
        return changed;
    }

    @Override
    public void releasePending(UUID userId) {
        for (SubscriptionOrder order : orders.findByUserIdAndOrderStatus(userId, OrderStatus.PENDING)) {
            apply(order, true);
        }
    }

    private void apply(SubscriptionOrder order) {
        apply(order, false);
    }

    /**
     * Asks the gateway about the order; a confirmed payment is settled. An unpaid order is closed when its window has
     * closed, or at once when {@code withdraw}ing it for a newer order: first cancelled at the gateway, so a page or QR
     * code the Trader still holds can no longer be paid. When the gateway refuses to cancel and the order still awaits
     * payment (a payment method it cannot cancel), the order stays PENDING, at most a day past its window.
     */
    private void apply(SubscriptionOrder order, boolean withdraw) {
        String code = order.getOrderCode();
        Instant now = clock.instant();
        Optional<PaymentGateway> gateway = gateways.orderedStream()
                .filter(g -> g.code() == order.getPaymentGateway())
                .filter(PaymentGateway::configured)
                .findFirst();
        if (gateway.isEmpty()) {
            close(code, withdraw);
            return;
        }
        GatewayLookup answer = gateway.get().lookup(code);
        if (answer.state() == GatewayLookup.State.PAID) {
            settlement.settle(answer.payment());
            return;
        }
        if (!withdraw && now.isBefore(order.getExpiresAt())) {
            return;
        }
        if (answer.state() == GatewayLookup.State.AWAITING && !gateway.get().cancel(code)) {
            GatewayLookup again = gateway.get().lookup(code);
            if (again.state() == GatewayLookup.State.PAID) {
                settlement.settle(again.payment());
                return;
            }
            if (again.state() == GatewayLookup.State.AWAITING
                    && (withdraw || now.isBefore(order.getExpiresAt().plus(CANCEL_GRACE)))) {
                log.warn("{} refused to cancel order {}, which still awaits payment", order.getPaymentGateway(), code);
                return;
            }
        }
        close(code, withdraw);
    }

    private void close(String orderCode, boolean withdraw) {
        if (withdraw) {
            settlement.withdraw(orderCode);
        } else {
            settlement.expireIfDue(orderCode);
        }
    }

    private Optional<SubscriptionOrder> find(String orderCode) {
        return orders.findByOrderCode(orderCode);
    }
}
