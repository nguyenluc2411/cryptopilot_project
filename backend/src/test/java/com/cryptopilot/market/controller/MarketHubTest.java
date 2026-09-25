package com.cryptopilot.market.controller;

import static com.cryptopilot.market.client.StubStreamServer.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.service.MarketBroadcastService;
import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * The market topics end to end: real STOMP clients on {@code /ws}, anonymous (D-50), subscribing and unsubscribing per
 * pair, receiving only their own pair's updates, one slow client holding nobody else, and subscriptions outside the
 * public market topics refused. The pushes are called directly and every wait is on an event — a subscription seen by
 * the broker, a message received — never on a length of time.
 *
 * <p>Rule: NSF-03; SRS 3.3.1, 4.2.3; TECHNICAL_DESIGN 9; D-50, D-51.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class MarketHubTest {

    private static final String BTC = "/topic/ticker.SPOT.BTCUSDT";
    private static final String ETH = "/topic/ticker.SPOT.ETHUSDT";

    @LocalServerPort
    private int port;

    @Autowired
    private MarketBroadcastService broadcast;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private final AtomicLong clock =
            new AtomicLong(Instant.parse("2026-09-25T10:00:00Z").toEpochMilli());

    @AfterEach
    void stop() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        // The broker drops a session's subscriptions when it hears of the disconnect; the next test starts from none.
        awaitSubscribers(BTC, 0);
        awaitSubscribers(ETH, 0);
        clients.forEach(WebSocketStompClient::stop);
    }

    /** An anonymous client subscribes to a pair, receives its updates, unsubscribes, and receives no more. */
    @Test
    void TD9_aClient_subscribesAndUnsubscribesPerPair() throws Exception {
        Client client = connect();
        Inbox btc = new Inbox(null);
        StompSession.Subscription subscription = client.session.subscribe(BTC, btc);
        awaitSubscribers(BTC, 1);

        push("BTCUSDT", "63000");
        await(() -> btc.payloads.size() == 1, "the update of the subscribed pair");
        assertThat((String) JsonPath.read(btc.payloads.get(0), "$.lastPrice")).isEqualTo("63000");

        subscription.unsubscribe();
        awaitSubscribers(BTC, 0);
        Inbox eth = new Inbox(null);
        client.session.subscribe(ETH, eth);
        awaitSubscribers(ETH, 1);
        push("BTCUSDT", "63001");
        push("ETHUSDT", "4000");

        // Publish order is kept per session: had BTC still been subscribed, its update would have come first.
        await(() -> eth.payloads.size() == 1, "the update of the other pair");
        assertThat(btc.payloads).hasSize(1);
    }

    /**
     * A client that subscribes and unsubscribes the same destination back to back, many times, leaves no subscription
     * behind: the broker must handle each session's frames in the order they were sent.
     */
    @Test
    void TD9_subscribeThenUnsubscribeBackToBack_leavesNoSubscription() throws Exception {
        Client client = connect();

        for (int i = 0; i < 300; i++) {
            client.session.subscribe(BTC, new Inbox(null)).unsubscribe();
        }

        awaitSubscribers(BTC, 0);
        Inbox probe = new Inbox(null);
        client.session.subscribe(ETH, probe);
        awaitSubscribers(ETH, 1);
        // Every frame sent before the probe has been handled; none of them may have left a BTC subscription.
        assertThat(subscribers(BTC)).isZero();
    }

    /** Each client receives the updates of the pairs it subscribed to, and nothing else. */
    @Test
    void TD9_updates_reachOnlyTheSubscribersOfTheirPair() throws Exception {
        Inbox btc = new Inbox(null);
        Inbox eth = new Inbox(null);
        connect().session.subscribe(BTC, btc);
        connect().session.subscribe(ETH, eth);
        awaitSubscribers(BTC, 1);
        awaitSubscribers(ETH, 1);

        push("ETHUSDT", "4000");
        push("BTCUSDT", "63000");
        push("BTCUSDT", "63001");

        await(() -> btc.payloads.size() == 2 && eth.payloads.size() == 1, "each client's updates");
        assertThat(btc.payloads).allSatisfy(json -> assertThat((String) JsonPath.read(json, "$.symbol"))
                .isEqualTo("BTCUSDT"));
        assertThat((String) JsonPath.read(eth.payloads.get(0), "$.symbol")).isEqualTo("ETHUSDT");
        assertThat((String) JsonPath.read(eth.payloads.get(0), "$.market")).isEqualTo("SPOT");
    }

    /** A client stuck on its first message holds nobody else: the other receives every update, in order. */
    @Test
    void TD9_aSlowClient_doesNotHoldTheOthers() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        Inbox slow = new Inbox(stuck);
        Inbox fast = new Inbox(null);
        connect().session.subscribe(BTC, slow);
        connect().session.subscribe(BTC, fast);
        awaitSubscribers(BTC, 2);

        for (int i = 0; i < 20; i++) {
            push("BTCUSDT", String.valueOf(63000 + i));
        }

        await(() -> fast.payloads.size() == 20, "every update at the fast client");
        assertThat(fast.payloads.stream().map(json -> (String) JsonPath.read(json, "$.lastPrice")))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 20)
                        .mapToObj(i -> String.valueOf(63000 + i))
                        .toList());
        assertThat(slow.payloads).as("still stuck on its first message").isEmpty();
        stuck.countDown();
        await(() -> slow.payloads.size() == 20, "the slow client catching up once released");
    }

    /** D-50: the market topics are public; anything else is refused and the session closed. */
    @Test
    void D50_aSubscriptionOutsideThePublicMarketTopics_isRefused() throws Exception {
        Client client = connect();

        client.session.subscribe("/topic/secret", new Inbox(null));

        await(() -> !client.errors.isEmpty(), "the error frame");
        assertThat(client.errors.get(0)).contains("not a public market topic");
        await(() -> !client.session.isConnected(), "the session to be closed");
    }

    /** No application destination accepts a message from a client. */
    @Test
    void D50_aSendFromAClient_isRefused() throws Exception {
        Client client = connect();

        client.session.send("/app/anything", "hello");

        await(() -> !client.errors.isEmpty(), "the error frame");
        await(() -> !client.session.isConnected(), "the session to be closed");
    }

    // ------------------------------------------------------------------ helpers

    /** One ticker update of a pair, pushed now. */
    private void push(String symbol, String last) {
        BigDecimal price = new BigDecimal(last);
        broadcast.publish(
                MarketType.SPOT,
                new TickerMessage(
                        symbol,
                        price,
                        price,
                        price,
                        price,
                        price,
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        Instant.ofEpochMilli(clock.incrementAndGet())));
        broadcast.pushUpdates();
    }

    /** Waits until the broker has exactly {@code count} subscriptions to a destination. */
    private void awaitSubscribers(String destination, int count) {
        await(() -> subscribers(destination) == count, count + " subscriptions to " + destination);
    }

    private int subscribers(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        return broker.getSubscriptionRegistry().findSubscriptions(probe).values().stream()
                .mapToInt(List::size)
                .sum();
    }

    private Client connect() throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new TextConverter());
        clients.add(stomp);
        List<String> errors = new CopyOnWriteArrayList<>();
        StompSession session = stomp.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        errors.add(String.valueOf(headers.getFirst("message")));
                    }

                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return String.class;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return new Client(session, errors);
    }

    private record Client(StompSession session, List<String> errors) {}

    /** The messages of one subscription, optionally stuck on the first until released. */
    private static final class Inbox implements StompFrameHandler {

        private final List<String> payloads = new CopyOnWriteArrayList<>();
        private final CountDownLatch gate;

        Inbox(CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            payloads.add((String) payload);
        }
    }

    /** Frames as text, both ways. */
    private static final class TextConverter implements MessageConverter {

        @Override
        public Object fromMessage(Message<?> message, Class<?> targetClass) {
            Object payload = message.getPayload();
            return payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : payload.toString();
        }

        @Override
        public Message<?> toMessage(Object payload, MessageHeaders headers) {
            return MessageBuilder.createMessage(payload.toString().getBytes(StandardCharsets.UTF_8), headers);
        }
    }
}
