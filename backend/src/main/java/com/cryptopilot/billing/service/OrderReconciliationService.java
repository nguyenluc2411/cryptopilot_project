package com.cryptopilot.billing.service;

import com.cryptopilot.billing.model.enums.OrderStatus;

/**
 * Settles the orders whose IPN never came by asking their gateway (NSF-13), and expires the ones nobody paid in time
 * (BR-54).
 *
 * <p>Rule: BR-53, BR-54; NSF-13; A-03; D-146.
 */
public interface OrderReconciliationService {

    /**
     * Asks the gateway about the order now, whatever its age, and settles or expires it as the answer says. An order
     * already PAID or FAILED is left alone; an EXPIRED one is asked about too, since it can still be paid (A-03).
     *
     * @return the order's status afterwards
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when there is no such order
     * @throws com.cryptopilot.common.exception.BusinessException {@code PAYMENT_GATEWAY_UNAVAILABLE} when the gateway
     *     cannot be asked
     */
    OrderStatus reconcile(String orderCode);

    /**
     * One scheduled run: the oldest PENDING orders past the reconciliation delay, each asked about once. A gateway
     * that cannot be asked leaves its order PENDING for the next run.
     *
     * @return how many orders changed status
     */
    int reconcileDue();
}
