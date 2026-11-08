package com.cryptopilot.billing.service;

/**
 * The callbacks payment gateways send: authenticated by the gateway's own means, never by a session (BR-53).
 *
 * <p>Rule: BR-53; NSF-12; D-146.
 */
public interface PaymentCallbackService {

    /**
     * Handles a SePay IPN.
     *
     * @param secretKey the {@code X-Secret-Key} header, or {@code null}
     * @param body the JSON body as received
     * @return false when the IPN does not carry this deployment's IPN secret key; nothing was read from it
     * @throws IllegalArgumentException when an authentic IPN is not a SePay notification
     */
    boolean acceptSePay(String secretKey, String body);
}
