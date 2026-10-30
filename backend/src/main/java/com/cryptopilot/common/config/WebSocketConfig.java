package com.cryptopilot.common.config;

import com.cryptopilot.common.service.TopicSubscriptionGuard;
import java.security.Principal;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * The realtime endpoint of TECHNICAL_DESIGN 9 and ADR-005: STOMP over WebSocket at {@code /ws}, no SockJS fallback, the
 * simple in-memory broker on {@code /topic} and {@code /queue}, application prefix {@code /app}, user prefix
 * {@code /user}.
 *
 * <h2>Who is connected</h2>
 *
 * <p>A client may connect anonymously, or send {@code Authorization: Bearer <access token>} in its {@code CONNECT}
 * frame. The token is checked by the same decoder as on REST — signature, expiry, issuer, and a session that has not
 * ended (D-33) — and becomes the session's user, named by the account's id as on REST. A token that fails is refused
 * with an ERROR frame, as REST refuses it, rather than taken as an anonymous connection. The check is made once, when
 * the session connects: a session outlives its access token, as a REST call that started before the expiry finishes.
 *
 * <h2>What a client may subscribe to</h2>
 *
 * <p>The public market topics — {@code /topic/ticker.*}, {@code /topic/kline.*}, {@code /topic/overview.*}, and the
 * trades and depth of the trading screen — open to anyone, as the market data is public (D-50). A signed-in session
 * may also subscribe to its own demo trading queues {@code /user/queue/orders}, {@code /user/queue/fills} and
 * {@code /user/queue/balances} (TR-08), to its paper Futures {@code /user/queue/futures.orders} and
 * {@code /user/queue/futures.account} (TR-41), to the changes of its plans' positions {@code /user/queue/positions} and to its
 * notifications {@code /user/queue/notifications}; the broker delivers there only what was sent to that session's
 * user. A topic that a module publishes for some users only — the analysis of NSF-05 (D-126) — is decided by that
 * module's {@link TopicSubscriptionGuard}. Every other subscription and every {@code SEND} is refused.
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
 * <h2>Origins</h2>
 *
 * <p>A browser may open {@code /ws} only from an origin of {@link CorsProperties}, the same list the REST API uses.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3 and 9; ADR-005; D-33; D-50; D-126; TR-08.
 * <p>Reference: Spring Framework reference documentation, "WebSocket &gt; STOMP" (simple broker, channel
 * interceptors, {@code setPreservePublishOrder}, send time and buffer limits).
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(RealtimeProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** The public market destinations of TECHNICAL_DESIGN 9, and the trades and depth of the trading screen (TR-03). */
    static final Pattern PUBLIC_TOPICS =
            Pattern.compile("/topic/(ticker|kline|overview|trades|depth)\\.[A-Za-z0-9._]+");

    /**
     * The queues of the signed-in user: demo trading (TR-08), paper Futures orders and account (TR-41), the positions of
     * the plans (UC-20) and the notifications
     * (SCR-15, Q-75). {@code /user/queue/...} is resolved to the caller's own session, so another user's queue cannot be
     * named here; {@code /user/{id}/queue/...} matches no pattern and is refused.
     */
    static final Pattern USER_QUEUES = Pattern.compile(
            "/user/queue/(orders|fills|balances|positions|notifications|futures\\.orders|futures\\.account)");

    private static final String BEARER = "Bearer ";

    private final RealtimeProperties properties;
    private final CorsProperties origins;
    private final JwtDecoder tokens;
    private final Converter<Jwt, AbstractAuthenticationToken> callers;
    private final ObjectProvider<TopicSubscriptionGuard> guards;

    public WebSocketConfig(
            RealtimeProperties properties,
            CorsProperties origins,
            JwtDecoder tokens,
            Converter<Jwt, AbstractAuthenticationToken> callers,
            ObjectProvider<TopicSubscriptionGuard> guards) {
        this.properties = properties;
        this.origins = origins;
        this.tokens = tokens;
        this.callers = callers;
        this.guards = guards;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins.allowedOrigins().toArray(String[]::new));
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
                if (frame == null) {
                    return message;
                }
                if (frame.getCommand() == StompCommand.CONNECT) {
                    String authorization = frame.getFirstNativeHeader("Authorization");
                    if (authorization != null) {
                        frame.setUser(caller(authorization));
                    }
                }
                requireAllowed(
                        frame.getCommand(),
                        frame.getDestination(),
                        frame.getUser(),
                        guards.orderedStream().toList());
                return message;
            }
        });
    }

    /** The signed-in caller of a bearer access token; refused when the token is not one this application accepts. */
    private Principal caller(String authorization) {
        if (!authorization.startsWith(BEARER)) {
            throw new MessageDeliveryException("the Authorization header is not a bearer access token");
        }
        try {
            AbstractAuthenticationToken caller =
                    callers.convert(tokens.decode(authorization.substring(BEARER.length())));
            if (caller == null) {
                throw new MessageDeliveryException("the access token names no caller");
            }
            return caller;
        } catch (JwtException refused) {
            throw new MessageDeliveryException("the access token is not valid: " + refused.getMessage());
        }
    }

    /**
     * Refuses any {@code SEND}, a subscription that the guard governing its destination refuses, a subscription to a
     * user queue without a signed-in user, and any other subscription outside the public market topics.
     */
    static void requireAllowed(
            StompCommand command, String destination, Principal user, List<TopicSubscriptionGuard> guards) {
        if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("no application destination accepts messages");
        }
        if (command != StompCommand.SUBSCRIBE) {
            return;
        }
        if (destination != null) {
            for (TopicSubscriptionGuard guard : guards) {
                if (guard.governs(destination)) {
                    guard.requireAllowed(destination, user);
                    return;
                }
            }
        }
        if (destination != null && USER_QUEUES.matcher(destination).matches()) {
            if (user == null) {
                throw new MessageDeliveryException("sign in to subscribe to " + destination);
            }
            return;
        }
        if (destination == null || !PUBLIC_TOPICS.matcher(destination).matches()) {
            throw new MessageDeliveryException("not a public market topic: " + destination);
        }
    }
}
