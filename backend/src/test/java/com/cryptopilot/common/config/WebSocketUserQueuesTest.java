package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;

/**
 * The queues of the signed-in user: the positions of the plans and the notifications are admitted for the user's own
 * session, refused to a guest, and another user's queue cannot be named.
 *
 * <p>Rule: UC-20; SCR-15; TR-41; TECHNICAL_DESIGN 9; Q-75.
 */
class WebSocketUserQueuesTest {

    /** The dot of a Futures queue is a dot, not any character. */
    @Test
    void TR41_aQueueNamedLikeAFuturesOneButNotIt_isRefused() {
        assertThatThrownBy(() ->
                        WebSocketConfig.requireAllowed(StompCommand.SUBSCRIBE, "/user/queue/futuresXorders", TRADER))
                .isInstanceOf(MessageDeliveryException.class);
    }

    private static final Principal TRADER = () -> UUID.randomUUID().toString();

    @ParameterizedTest
    @ValueSource(
            strings = {"positions", "notifications", "orders", "fills", "balances", "futures.orders", "futures.account"
            })
    void UC20_aSignedInSession_subscribesToItsOwnQueue(String queue) {
        assertThatNoException()
                .isThrownBy(
                        () -> WebSocketConfig.requireAllowed(StompCommand.SUBSCRIBE, "/user/queue/" + queue, TRADER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"positions", "notifications"})
    void UC20_aGuest_isRefused(String queue) {
        assertThatThrownBy(() -> WebSocketConfig.requireAllowed(StompCommand.SUBSCRIBE, "/user/queue/" + queue, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("sign in to subscribe");
    }

    @ParameterizedTest
    @ValueSource(strings = {"positions", "notifications", "futures.account"})
    void UC20_anotherUsersQueue_isRefused(String queue) {
        String other = "/user/" + UUID.randomUUID() + "/queue/" + queue;

        assertThatThrownBy(() -> WebSocketConfig.requireAllowed(StompCommand.SUBSCRIBE, other, TRADER))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("not a public market topic");
    }
}
