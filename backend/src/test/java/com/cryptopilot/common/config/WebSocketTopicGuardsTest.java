package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.service.TopicSubscriptionGuard;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;

/**
 * A topic that a module governs is decided by that module's guard alone; the other rules still apply to everything
 * else.
 *
 * <p>Rule: TECHNICAL_DESIGN 9; D-126.
 */
class WebSocketTopicGuardsTest {

    private static final Principal TRADER = () -> UUID.randomUUID().toString();

    /** Governs {@code /topic/guarded.*}: admits a signed-in session, refuses an anonymous one. */
    private static final TopicSubscriptionGuard GUARDED = new TopicSubscriptionGuard() {
        @Override
        public boolean governs(String destination) {
            return destination.startsWith("/topic/guarded.");
        }

        @Override
        public void requireAllowed(String destination, Principal user) {
            if (user == null) {
                throw new MessageDeliveryException("guarded");
            }
        }
    };

    @Test
    void D126_aGovernedTopic_isAdmittedByItsGuard() {
        assertThatNoException()
                .isThrownBy(() -> WebSocketConfig.requireAllowed(
                        StompCommand.SUBSCRIBE, "/topic/guarded.x", TRADER, List.of(GUARDED)));
    }

    @Test
    void D126_aGovernedTopic_isRefusedByItsGuard() {
        assertThatThrownBy(() -> WebSocketConfig.requireAllowed(
                        StompCommand.SUBSCRIBE, "/topic/guarded.x", null, List.of(GUARDED)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("guarded");
    }

    @Test
    void D126_aTopicNoGuardGoverns_followsThePublicRule() {
        assertThatNoException()
                .isThrownBy(() -> WebSocketConfig.requireAllowed(
                        StompCommand.SUBSCRIBE, "/topic/kline.SPOT.BTCUSDT.1h", null, List.of(GUARDED)));
        assertThatThrownBy(() -> WebSocketConfig.requireAllowed(
                        StompCommand.SUBSCRIBE, "/topic/other.x", TRADER, List.of(GUARDED)))
                .hasMessageContaining("not a public market topic");
    }

    @Test
    void D126_aSendToAGovernedTopic_isStillRefused() {
        assertThatThrownBy(() ->
                        WebSocketConfig.requireAllowed(StompCommand.SEND, "/topic/guarded.x", TRADER, List.of(GUARDED)))
                .hasMessageContaining("no application destination");
    }
}
