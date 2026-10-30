package com.cryptopilot.market.controller;

import static com.cryptopilot.market.client.StubStreamServer.await;
import static com.cryptopilot.market.client.StubStreamServer.signal;
import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.Kline;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.model.StreamTarget;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.StreamCandleService;
import com.cryptopilot.support.PlanTraders;
import com.cryptopilot.support.StompRecorder;
import com.cryptopilot.support.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * The analysis topics of D-126 end to end: real STOMP clients on {@code /ws}, anonymous or signed in on
 * {@code CONNECT} as Traders on each plan tier, and closed candles fed through NSF-03 so that NSF-05 stores and pushes
 * them. A refused subscription closes its session, so each refusal has a session of its own. Every wait is on an
 * event, never on a length of time.
 *
 * <p>Rule: NSF-05, BR-62; SRS CR-08; TECHNICAL_DESIGN 9; D-60, D-126.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class AnalysisTopicTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    @Autowired
    private StreamCandleService streamCandles;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private PlanTraders traders;
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        traders = new PlanTraders(sql, jwtEncoder, Instant.now());
        symbol = "AT" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, true, "TRADING", "TRADING", 0);
    }

    @AfterEach
    void removeRows() {
        try {
            traders.clear();
            sql.sql("delete from technical_indicator where pair_id = ?")
                    .param(pair)
                    .update();
            sql.sql("delete from ohlcv where pair_id = ?").param(pair).update();
            UUID base = sql.sql("select base_coin_id from crypto_pair where pair_id = ?")
                    .param(pair)
                    .query(UUID.class)
                    .single();
            sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
            sql.sql("delete from coin where coin_id = ?").param(base).update();
        } finally {
            for (StompSession session : sessions) {
                try {
                    session.disconnect();
                } catch (RuntimeException closedByTheServer) {
                    // A refused session is closed by the server; there is nothing left to disconnect.
                }
            }
            clients.forEach(WebSocketStompClient::stop);
        }
    }

    /** D-126 (3): without a token the public market topics work as before; the analysis needs a Trader. */
    @Test
    void D126_withoutAToken_thePublicTopicsWork_andTheAnalysisIsRefused() throws Exception {
        Client guest = connect(null);
        String kline = "/topic/kline.SPOT." + symbol + ".1h";
        guest.session.subscribe(kline, new Inbox(new CopyOnWriteArrayList<>()));
        await(() -> subscribers(kline) == 1, "the guest's kline subscription");

        Client refused = connect(null);
        refused.session.subscribe(main(MarketType.SPOT), new Inbox(new CopyOnWriteArrayList<>()));

        refused.recorder().awaitRefused(refused.session(), "sign in as a Trader");
        assertThat(subscribers(main(MarketType.SPOT))).isZero();
        assertThat(guest.session.isConnected()).isTrue();
    }

    /** D-126 (3): a token this application did not sign is refused on CONNECT, not taken as an anonymous session. */
    @Test
    void D126_aConnectWithAnInvalidToken_isRefused() {
        StompRecorder recorder = new StompRecorder();
        StompHeaders connect = new StompHeaders();
        connect.add(HttpHeaders.AUTHORIZATION, "Bearer not-a-token");
        stompClient().connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect, recorder);

        recorder.awaitRefused("access token is not valid");
    }

    /** BR-62: a FREE Trader has the Spot analysis, but neither the Futures analysis nor the components (CR-08). */
    @Test
    void BR62_aFreeTrader_getsTheSpotTopic_only() throws Exception {
        String bearer = traders.bearer(PlanTier.FREE);
        Client spot = connect(bearer);
        spot.session.subscribe(main(MarketType.SPOT), new Inbox(new CopyOnWriteArrayList<>()));
        await(() -> subscribers(main(MarketType.SPOT)) == 1, "the FREE Trader's Spot subscription");

        Client futures = connect(bearer);
        futures.session.subscribe(main(MarketType.FUTURES), new Inbox(new CopyOnWriteArrayList<>()));
        futures.recorder().awaitRefused(futures.session(), "FUTURES_ANALYSIS");

        Client components = connect(bearer);
        components.session.subscribe(components(MarketType.SPOT), new Inbox(new CopyOnWriteArrayList<>()));
        components.recorder().awaitRefused(components.session(), "SCORE_COMPONENTS");

        assertThat(subscribers(main(MarketType.FUTURES))).isZero();
        assertThat(subscribers(components(MarketType.SPOT))).isZero();
    }

    /** BR-62: PRO and PREMIUM include the Futures analysis and the components, on both markets. */
    @ParameterizedTest
    @EnumSource(
            value = PlanTier.class,
            names = {"PRO", "PREMIUM"})
    void BR62_aPaidTrader_getsFuturesAndComponents(PlanTier tier) throws Exception {
        Client client = connect(traders.bearer(tier));
        List<String> topics = List.of(
                main(MarketType.SPOT),
                components(MarketType.SPOT),
                main(MarketType.FUTURES),
                components(MarketType.FUTURES));
        for (String topic : topics) {
            client.session.subscribe(topic, new Inbox(new CopyOnWriteArrayList<>()));
        }

        await(() -> topics.stream().allMatch(topic -> subscribers(topic) == 1), "the four subscriptions of " + tier);
        assertThat(client.session.isConnected()).isTrue();
    }

    /**
     * NSF-05: a closed candle is pushed once on each topic, however often its close is announced, and a forming candle
     * never. The next closed candle closes the check: the broker keeps the order of each session, so anything pushed
     * for the earlier frames has arrived before it.
     */
    @Test
    void NSF05_aClosedCandle_isPushedOnce_andAFormingCandleNever() throws Exception {
        Client client = connect(traders.bearer(PlanTier.PRO));
        List<String> mainInbox = new CopyOnWriteArrayList<>();
        List<String> componentsInbox = new CopyOnWriteArrayList<>();
        client.session.subscribe(main(MarketType.SPOT), new Inbox(mainInbox));
        client.session.subscribe(components(MarketType.SPOT), new Inbox(componentsInbox));
        await(
                () -> subscribers(main(MarketType.SPOT)) == 1 && subscribers(components(MarketType.SPOT)) == 1,
                "the two subscriptions");
        Instant first = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(3));
        Instant second = first.plus(Duration.ofHours(1));

        assertThat(kline(first, false)).isFalse();
        assertThat(kline(first, true)).isTrue();
        assertThat(kline(first, true)).isTrue();
        assertThat(kline(second, false)).isFalse();
        assertThat(kline(second, true)).isTrue();

        await(() -> mainInbox.size() >= 2 && componentsInbox.size() >= 2, "the pushes of the two closed candles");
        assertThat(mainInbox.stream().map(body -> JsonPath.<String>read(body, "$.openTime")))
                .containsExactly(first.toString(), second.toString());
        assertThat(componentsInbox.stream().map(body -> JsonPath.<String>read(body, "$.openTime")))
                .containsExactly(first.toString(), second.toString());
    }

    /** D-126 (1): the main payload carries the indicators and levels of the candle, never a score or components. */
    @Test
    void D126_theMainPayload_hasNoScoreAndNoComponents() throws Exception {
        Client client = connect(traders.bearer(PlanTier.PREMIUM));
        List<String> mainInbox = new CopyOnWriteArrayList<>();
        List<String> componentsInbox = new CopyOnWriteArrayList<>();
        client.session.subscribe(main(MarketType.SPOT), new Inbox(mainInbox));
        client.session.subscribe(components(MarketType.SPOT), new Inbox(componentsInbox));
        await(
                () -> subscribers(main(MarketType.SPOT)) == 1 && subscribers(components(MarketType.SPOT)) == 1,
                "the two subscriptions");
        Instant open = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(2));

        kline(open, true);

        await(() -> !mainInbox.isEmpty() && !componentsInbox.isEmpty(), "the pushes of the candle");
        Map<String, Object> body = JsonPath.read(mainInbox.getFirst(), "$");
        assertThat(body)
                .containsKeys("market", "symbol", "timeframe", "openTime", "indicators")
                .doesNotContainKeys("components", "setupScore", "score", "componentsLocked");
        assertThat(body)
                .containsEntry("market", "SPOT")
                .containsEntry("symbol", symbol)
                .containsEntry("timeframe", "1h");
        Map<String, Object> components = JsonPath.read(componentsInbox.getFirst(), "$.components");
        assertThat(components).containsKeys("trend", "momentum", "volume", "level");
    }

    /** A 1h Spot candle of the pair, at 100, through NSF-03; whether it was a stored close. */
    private boolean kline(Instant open, boolean closed) {
        BigDecimal price = new BigDecimal("100");
        Kline candle = new Kline(
                open,
                open.plus(Duration.ofHours(1)).minusMillis(1),
                price,
                price,
                price,
                price,
                BigDecimal.TEN,
                new BigDecimal("1000"),
                10);
        return streamCandles.onKline(
                MarketType.SPOT,
                new StreamTarget(pair, symbol),
                new KlineMessage(symbol, MarketInterval.ONE_HOUR, candle, closed, Instant.now()));
    }

    private String main(MarketType market) {
        return "/topic/analysis." + market.name() + "." + symbol + ".1h";
    }

    private String components(MarketType market) {
        return main(market) + ".components";
    }

    private int subscribers(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        return broker.getSubscriptionRegistry().findSubscriptions(probe).values().stream()
                .mapToInt(List::size)
                .sum();
    }

    private Client connect(String bearer) throws Exception {
        StompRecorder recorder = new StompRecorder();
        StompHeaders connect = new StompHeaders();
        if (bearer != null) {
            connect.add(HttpHeaders.AUTHORIZATION, bearer);
        }
        StompSession session = stompClient()
                .connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect, recorder)
                .get(5, TimeUnit.SECONDS); // ends when the session connects; the bound only stops a hang
        sessions.add(session);
        return new Client(session, recorder);
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new TextConverter());
        clients.add(stomp);
        return stomp;
    }

    private record Client(StompSession session, StompRecorder recorder) {}

    private static final class Inbox implements StompFrameHandler {

        private final List<String> payloads;

        Inbox(List<String> payloads) {
            this.payloads = payloads;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            payloads.add((String) payload);
            signal();
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
