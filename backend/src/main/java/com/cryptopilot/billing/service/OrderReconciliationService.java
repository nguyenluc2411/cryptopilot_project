package com.cryptopilot.billing.service;

import com.cryptopilot.billing.model.enums.OrderStatus;
import java.util.UUID;

/**
 * Settles the orders whose IPN never came by asking their gateway (NSF-13), and expires the ones nobody paid in time
 * (BR-54).
 *
 * <p>Rule: BR-53, BR-54; NSF-13; STM-06; D-147.
 */
public interface OrderReconciliationService {

    /**
     * Asks the gateway about the order now, whatever its age, and settles or expires it as the answer says. An order
     * already PAID or FAILED is left alone; an EXPIRED one is asked about too, and a payment found for it is kept for
     * an administrator.
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

    /**
     * Clears the way for a new order of the Trader (one PENDING order per Trader, D-147): each PENDING order is asked
     * about; a paid one is settled, a cancelled one fails, and an unpaid one is cancelled at its gateway and withdrawn.
     * An order the gateway cannot cancel stays PENDING until its window closes (MoMo, or a card payment under way),
     * and the new order is refused; after the window it expires and the new order goes ahead (BR-54).
     *
     * @throws com.cryptopilot.billing.client.GatewayUnavailableException when a gateway cannot be asked
     */
    void releasePending(UUID userId);
}
