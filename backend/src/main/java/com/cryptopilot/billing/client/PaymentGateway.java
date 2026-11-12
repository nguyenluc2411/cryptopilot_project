package com.cryptopilot.billing.client;

import com.cryptopilot.billing.model.Checkout;
import com.cryptopilot.billing.model.CheckoutRequest;
import com.cryptopilot.billing.model.GatewayLookup;
import com.cryptopilot.billing.model.enums.GatewayCode;

/**
 * A payment gateway the billing module charges through: the anti-corruption layer between an order and the gateway's
 * own protocol. Each gateway is a bean; one that is not configured on a deployment (no credentials) reports so and is
 * offered to nobody.
 *
 * <p>Rule: TECHNICAL_DESIGN 7.11; D-147.
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

    /**
     * What the gateway knows of the order with this code, for reconciliation (NSF-13). Called outside any transaction.
     *
     * @throws GatewayUnavailableException when the gateway is not configured, cannot be reached or refuses the query
     */
    GatewayLookup lookup(String orderCode);

    /**
     * Asks the gateway to stop taking payment for the order, so a page or QR code the Trader still holds can no longer
     * be paid. Called outside any transaction.
     *
     * @return {@code true} when nothing is payable any more (cancelled now or before, or never opened), {@code false}
     *     when the gateway refused (already paid, or a payment method it cannot cancel): look the order up again
     * @throws GatewayUnavailableException when the gateway is not configured or cannot be reached
     */
    boolean cancel(String orderCode);
}
