package com.cryptopilot.common.config;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * The realtime endpoint of TECHNICAL_DESIGN 9 and ADR-005: STOMP over WebSocket at {@code /ws}, no SockJS fallback, the
 * simple in-memory broker on {@code /topic} and {@code /queue}, application prefix {@code /app}, user prefix
 * {@code /user}.
 *
 * <h2>What a client may subscribe to</h2>
 *
 * <p>The public market topics only — {@code /topic/ticker.*}, {@code /topic/kline.*}, {@code /topic/overview.*} — open
 * to anyone, as the market data is public (D-50). Every other subscription and every {@code SEND} is refused: the user
 * destinations of TECHNICAL_DESIGN 9 do not exist yet, and the task that adds them adds the bearer check of the
 * {@code CONNECT} frame with them.
 *
 * <h2>Order and slow clients</h2>
 *
 * <p>The broker preserves publish order per session, so a client sees a pair's updates in the order they were pushed.
 * Spring's receive-order option is deliberately not used — with it, an exception of the inbound interceptor is raised on
 * another thread and the client never receives the ERROR frame of a refused subscription; the inbound channel runs on a
 * single thread instead, which keeps each session's frames in order and the ERROR frames intact.
 * A session that cannot keep up is buffered up to {@code sendBufferSizeLimit} and for up to {@code sendTimeLimit}, then
 * closed by Spring's session decorator; the others are not held.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3 and 9; ADR-005; D-50.
 * <p>Reference: Spring Framework reference documentation, "WebSocket &gt; STOMP" (simple broker, channel
 * interceptors, {@code setPreservePublishOrder}, send time and buffer limits).
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(RealtimeProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** The public market destinations of TECHNICAL_DESIGN 9. */
    static final Pattern PUBLIC_TOPICS = Pattern.compile("/topic/(ticker|kline|overview)\\.[A-Za-z0-9._]+");

    private final RealtimeProperties properties;

    public WebSocketConfig(RealtimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(properties.allowedOriginPatterns().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit((int) properties.sendTimeLimit().toMillis());
        registration.setSendBufferSizeLimit(
                (int) properties.sendBufferSizeLimit().toBytes());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // One thread handles the inbound frames of every session, first in first out: a SUBSCRIBE followed at once by
        // its
        // UNSUBSCRIBE is handled in that order. On Spring's default pool the two could swap and leave the subscription
        // registered for good. The interceptor below still runs on the thread that received the frame, so a refusal is
        // still answered with an ERROR frame. Inbound frames are subscriptions only, so one thread is plenty.
        registration.taskExecutor().corePoolSize(1).maxPoolSize(1);
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor frame = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (frame != null) {
                    requireAllowed(frame.getCommand(), frame.getDestination());
                }
                return message;
            }
        });
    }

    /** Refuses a subscription outside the public market topics, and any {@code SEND}. */
    static void requireAllowed(StompCommand command, String destination) {
        if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("no application destination accepts messages");
        }
        if (command == StompCommand.SUBSCRIBE
                && (destination == null || !PUBLIC_TOPICS.matcher(destination).matches())) {
            throw new MessageDeliveryException("not a public market topic: " + destination);
        }
    }
}
