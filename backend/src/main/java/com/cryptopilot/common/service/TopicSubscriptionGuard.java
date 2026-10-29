package com.cryptopilot.common.service;

import java.security.Principal;

/**
 * A module's rule for subscribing to topics it publishes beyond the public market data. {@code common} declares it so
 * that the realtime endpoint can ask the module that owns a topic without depending on that module; each guard is a
 * bean of its module.
 *
 * <p>Rule: TECHNICAL_DESIGN 9; D-126.
 */
public interface TopicSubscriptionGuard {

    /** Whether this guard decides subscriptions to {@code destination}; no other rule is asked about it then. */
    boolean governs(String destination);

    /**
     * Refuses a subscription the session may not hold.
     *
     * @param destination a destination this guard {@link #governs}
     * @param user the user the session connected as, or {@code null} for an anonymous session
     * @throws org.springframework.messaging.MessageDeliveryException when the subscription is refused; the client
     *     receives an ERROR frame and no message of the destination
     */
    void requireAllowed(String destination, Principal user);
}
