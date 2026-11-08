package com.cryptopilot.billing.client;

import com.cryptopilot.billing.model.Checkout;
import com.cryptopilot.billing.model.CheckoutRequest;
import com.cryptopilot.billing.model.enums.GatewayCode;

/**
 * A payment gateway the billing module charges through: the anti-corruption layer between an order and the gateway's
 * own protocol. Each gateway is a bean; one that is not configured on a deployment (no credentials) reports so and is
 * offered to nobody.
 *
 * <p>Rule: TECHNICAL_DESIGN 7.11; D-146.
 */
public interface PaymentGateway {

    /** Which gateway this is. */
    GatewayCode code();

    /** Whether this deployment holds the gateway's credentials. */
    boolean configured();

    /**
     * How the Trader's browser reaches the payment page for the order.
     *
     * @throws GatewayUnavailableException when the gateway cannot be reached or refuses the request
     */
    Checkout checkout(CheckoutRequest request);
}
