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
import java.util.List;
import java.util.Optional;
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
 * <p>Only a confirmed payment settles anything. A cancelled page, an order still awaiting payment, or one the gateway
 * never saw just expires when its window has closed; it is never failed here, so a payment that still arrives later is
 * honoured (A-03). A gateway this deployment cannot ask (not configured) lets its orders expire when they are due.
 *
 * <p>Rule: BR-53, BR-54; NSF-13; A-03; D-146.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class OrderReconciliationServiceImpl implements OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationServiceImpl.class);

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

    private void apply(SubscriptionOrder order) {
        Optional<PaymentGateway> gateway = gateways.orderedStream()
                .filter(g -> g.code() == order.getPaymentGateway())
                .filter(PaymentGateway::configured)
                .findFirst();
        if (gateway.isEmpty()) {
            settlement.expireIfDue(order.getOrderCode());
            return;
        }
        GatewayLookup answer = gateway.get().lookup(order.getOrderCode());
        if (answer.state() == GatewayLookup.State.PAID) {
            settlement.settle(answer.payment());
        } else {
            settlement.expireIfDue(order.getOrderCode());
        }
    }

    private Optional<SubscriptionOrder> find(String orderCode) {
        return orders.findByOrderCode(orderCode);
    }
}
