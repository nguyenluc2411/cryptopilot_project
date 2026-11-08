package com.cryptopilot.billing.client;

/** The gateway could not be reached, or refused to open a payment page; the Trader may try another gateway. */
public class GatewayUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public GatewayUnavailableException(String message) {
        super(message);
    }
}
