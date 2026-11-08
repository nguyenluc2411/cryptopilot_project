package com.cryptopilot.billing.service;

import com.cryptopilot.billing.model.PaymentResult;

/**
 * Settles an order from what its gateway reported, exactly once (BR-53), and on a confirmed payment changes the
 * Trader's subscription (D-146).
 *
 * <p>Rule: BR-53, BR-55, BR-56, BR-63 as amended by D-146; NSF-12; A-03.
 */
public interface PaymentSettlementService {

    /**
     * Applies a gateway report. A report about an unknown order, from another gateway, or about an order already PAID
     * or FAILED changes nothing.
     */
    void settle(PaymentResult result);

    /**
     * Expires the order with this code when it is still PENDING and its payment window has closed (BR-54). It stays
     * payable: a payment reported afterwards is still honoured (A-03).
     *
     * @return whether the order was expired by this call
     */
    boolean expireIfDue(String orderCode);

    /**
     * Withdraws the order with this code when it is still PENDING, whatever its window: the Trader started another
     * order and the gateway confirmed nothing is payable any more (D-146). It stays payable (A-03).
     *
     * @return whether the order was withdrawn by this call
     */
    boolean withdraw(String orderCode);
}
