package com.cryptopilot.billing.service;

import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.enums.GatewayCode;

/**
 * Settles an order from what its gateway reported, exactly once (BR-53), and on a confirmed payment changes the
 * Trader's subscription (D-147).
 *
 * <p>Rule: BR-53, BR-55, BR-56, BR-63 as amended by D-147; NSF-12; STM-06.
 */
public interface PaymentSettlementService {

    /**
     * Applies a gateway report. A report about an unknown order, from another gateway, or for another amount changes
     * nothing and is audited. A payment for an order already EXPIRED or FAILED is kept for an administrator and
     * audited; a repeated report for a PAID order changes nothing.
     */
    void settle(PaymentResult result);

    /**
     * Expires the order with this code when it is still PENDING and its payment window has closed (BR-54). A payment
     * reported afterwards is kept for an administrator.
     *
     * @return whether the order was expired by this call
     */
    boolean expireIfDue(String orderCode);

    /**
     * Withdraws the order with this code when it is still PENDING, whatever its window: the Trader started another
     * order and the gateway confirmed nothing is payable any more (D-147).
     *
     * @return whether the order was withdrawn by this call
     */
    boolean withdraw(String orderCode);

    /**
     * Audits a callback refused before it reached an order: its signature or credential is not this deployment's
     * (NSF-12).
     *
     * @param reason why it was refused, e.g. {@code BAD_SIGNATURE}
     */
    void auditRejected(GatewayCode gateway, String reason);
}
