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
}
