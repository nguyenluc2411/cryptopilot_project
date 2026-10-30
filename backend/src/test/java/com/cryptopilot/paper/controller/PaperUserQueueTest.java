package com.cryptopilot.paper.controller;

import static com.cryptopilot.market.client.StubStreamServer.await;
import static com.cryptopilot.market.client.StubStreamServer.signal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.PriceCacheService;
import com.cryptopilot.paper.config.PaperAccountProperties;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.service.PaperMatchingService;
import com.cryptopilot.support.StompRecorder;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * The demo trading queues end to end: real STOMP clients on {@code /ws}, signed in with an access token in the
 * {@code CONNECT} frame, receiving the orders, fills and balances their own trades change, and nothing of another
 * Trader's; a placement that is refused pushes nothing; a session without a valid token cannot subscribe. Every wait
 * is on an event — a subscription seen by the broker, a message received — never on a length of time.
 *
 * <p>Each test trades a pair of its own, {@code PQ…USDT} with a tick of 0.01, a step of 0.001 and a minimum notional
 * of 5. Nothing is rolled back: {@link #removeRows()} deletes every row the test wrote.
 *
 * <p>Rule: TR-08; TECHNICAL_DESIGN 9; D-33.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PaperUserQueueTest {

    private static final List<String> GRANTED = List.of("USDT", "BTC", "ETH", "BNB");
    private static final List<String> QUEUES = List.of("orders", "fills", "balances");
    private static final AtomicLong TICKS = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PriceCacheService prices;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private PaperMatchingService matching;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    @Autowired
    private SimpUserRegistry users;

    @Autowired
    private PaperAccountProperties grants;

    private final List<UUID> traders = new ArrayList<>();
    private final List<String> preStoredCoins = new ArrayList<>();
    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        preStoredCoins.addAll(sql.sql("select symbol from coin where symbol in ('USDT', 'BTC', 'ETH', 'BNB')")
                .query(String.class)
                .list());
        symbol = "PQ" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, false, "TRADING", null, 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5
                         where pair_id = ?""").param(pair).update();
    }

    @AfterEach
    void removeRows() {
        // The rows go first: a session the server already closed must not leave them for the tests that follow.
        try {
            deleteRows();
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

    private void deleteRows() {
        for (UUID trader : traders) {
            for (String table : List.of(
                    "order_fill",
                    "trade_order",
                    "order_list",
                    "wallet_ledger_entry",
                    "wallet_balance",
                    "equity_snapshot")) {
                sql.sql("delete from " + table + " where user_id = ?")
                        .param(trader)
                        .update();
            }
            sql.sql("delete from user_account where user_id = ?").param(trader).update();
        }
        UUID base = sql.sql("select base_coin_id from crypto_pair where pair_id = ?")
                .param(pair)
                .query(UUID.class)
                .single();
        sql.sql("delete from crypto_pair where pair_id = ?").param(pair).update();
        sql.sql("delete from coin where coin_id = ?").param(base).update();
        // The seed and schema tests expect no coin they did not write, so the coins an opening stored go again.
        for (String coin : GRANTED) {
            if (!preStoredCoins.contains(coin)) {
                sql.sql("delete from coin where symbol = ?").param(coin).update();
            }
        }
        Set<String> keys = redis.keys("px:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** A MARKET buy pushes the filled order, its fill, and the two balances it moved, to the Trader who placed it. */
    @Test
    void TR08_aMarketBuy_pushesTheOrder_theFill_andTheBalances() throws Exception {
        UUID trader = trader();
        Queues queues = subscribed(trader);
        price("100");

        String orderId = id(place(trader, market("BUY", "2")).andExpect(status().isCreated()));

        await(() -> queues.orders.size() == 1 && queues.fills.size() == 1 && queues.balances.size() == 2, "pushes");
        String order = queues.orders.get(0);
        assertThat((String) JsonPath.read(order, "$.orderId")).isEqualTo(orderId);
        assertThat((String) JsonPath.read(order, "$.status")).isEqualTo("FILLED");
        assertThat((String) JsonPath.read(order, "$.symbol")).isEqualTo(symbol);
        String fill = queues.fills.get(0);
        assertThat((String) JsonPath.read(fill, "$.orderId")).isEqualTo(orderId);
        assertThat((String) JsonPath.read(fill, "$.liquidity")).isEqualTo("TAKER");
        assertThat(decimal(JsonPath.read(fill, "$.price"))).isEqualByComparingTo("100");
        // 2 × 100 USDT paid out of the opening USDT; 2 bought less the 0.1 % taker fee taken in the coin bought.
        Map<String, Object> usdt = balance(queues, "USDT");
        assertThat(usdt).containsEntry("wallet", "SPOT");
        assertThat(decimal(usdt.get("free"))).isEqualByComparingTo(openingUsdt().subtract(new BigDecimal("200")));
        assertThat(decimal(balance(queues, baseAsset()).get("free"))).isEqualByComparingTo("1.998");
    }

    /** A LIMIT order pushes when it rests, locking its quote, and again when the engine fills it as a maker. */
    @Test
    void TR08_aRestingLimit_isPushedWhenPlaced_andWhenTheEngineFillsIt() throws Exception {
        UUID trader = trader();
        Queues queues = subscribed(trader);
        price("100");

        String orderId = id(place(trader, limit("BUY", "90", "1")).andExpect(status().isCreated()));
        await(() -> queues.orders.size() == 1 && queues.balances.size() == 1, "the resting order and its lock");
        assertThat((String) JsonPath.read(queues.orders.get(0), "$.status")).isEqualTo("NEW");
        assertThat(decimal(balance(queues, "USDT").get("locked"))).isEqualByComparingTo("90");
        assertThat(queues.fills).isEmpty();

        RestingOrder resting = matching.restingOrders().stream()
                .filter(order -> order.orderId().toString().equals(orderId))
                .findFirst()
                .orElseThrow();
        assertThat(matching.fill(resting, Instant.now(), FillSource.LIVE)).isTrue();

        await(() -> queues.orders.size() == 2 && queues.fills.size() == 1, "the fill of the engine");
        assertThat((String) JsonPath.read(queues.orders.get(1), "$.status")).isEqualTo("FILLED");
        assertThat((String) JsonPath.read(queues.fills.get(0), "$.liquidity")).isEqualTo("MAKER");
    }

    /** A cancel pushes the cancelled order and the balance it gave back. */
    @Test
    void TR08_aCancel_pushesTheOrder_andTheBalanceItFreed() throws Exception {
        UUID trader = trader();
        price("100");
        String orderId = id(place(trader, limit("BUY", "90", "1")).andExpect(status().isCreated()));
        Queues queues = subscribed(trader);

        as(trader, post("/api/v1/paper/orders/" + orderId + "/cancel"), null).andExpect(status().isOk());

        await(() -> queues.orders.size() == 1 && queues.balances.size() == 1, "the cancel");
        assertThat((String) JsonPath.read(queues.orders.get(0), "$.status")).isEqualTo("CANCELED");
        assertThat(decimal(balance(queues, "USDT").get("locked"))).isEqualByComparingTo("0");
    }

    /**
     * Each Trader receives only their own changes, and a refused placement, rolled back, pushes nothing: the first
     * order a Trader receives is the one placed after the refusal.
     */
    @Test
    void TR08_pushesReachOnlyTheirTrader_andARefusedOrderPushesNothing() throws Exception {
        UUID buyer = trader();
        UUID other = trader();
        Queues buyerQueues = subscribed(buyer);
        Queues otherQueues = subscribed(other);
        price("100");

        place(buyer, market("BUY", "500")).andExpect(status().isBadRequest());
        String placed = id(place(buyer, market("BUY", "1")).andExpect(status().isCreated()));

        await(() -> buyerQueues.orders.size() == 1, "the buyer's order");
        assertThat((String) JsonPath.read(buyerQueues.orders.get(0), "$.orderId"))
                .isEqualTo(placed);
        // The other Trader's own order arrives; had the buyer's reached them, it would have come first.
        String own = id(place(other, market("BUY", "1")).andExpect(status().isCreated()));
        await(() -> otherQueues.orders.size() == 1, "the other Trader's order");
        assertThat((String) JsonPath.read(otherQueues.orders.get(0), "$.orderId"))
                .isEqualTo(own);
    }

    /** A session without a signed-in user cannot subscribe to a user queue; it is refused and closed. */
    @Test
    void TR08_anAnonymousSession_cannotSubscribeToAUserQueue() throws Exception {
        Client client = connect(null);

        client.session.subscribe("/user/queue/orders", new Inbox());

        client.recorder().awaitRefused(client.session(), "sign in to subscribe");
    }

    /** Another user's queue cannot be named directly, signed in or not. */
    @Test
    void TR08_anotherUsersQueue_cannotBeNamed() throws Exception {
        UUID trader = trader();
        Client client = connect(bearer(trader));

        client.session.subscribe("/user/" + UUID.randomUUID() + "/queue/orders", new Inbox());

        client.recorder().awaitRefused(client.session(), "not a public market topic");
    }

    /** A CONNECT with a token this application did not sign is refused, not taken as an anonymous session. */
    @Test
    void TR08_aConnectWithAnInvalidToken_isRefused() throws Exception {
        StompRecorder recorder = new StompRecorder();
        WebSocketStompClient stomp = stompClient();
        StompHeaders connect = new StompHeaders();
        connect.add(HttpHeaders.AUTHORIZATION, "Bearer not-a-token");
        stomp.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect, recorder);

        recorder.awaitRefused("access token is not valid");
    }

    // ------------------------------------------------------------------ helpers

    /** A session of the Trader subscribed to the three queues, once the broker holds the three subscriptions. */
    private Queues subscribed(UUID trader) throws Exception {
        Client client = connect(bearer(trader));
        Queues queues = new Queues();
        client.session.subscribe("/user/queue/orders", queues.inbox(queues.orders));
        client.session.subscribe("/user/queue/fills", queues.inbox(queues.fills));
        client.session.subscribe("/user/queue/balances", queues.inbox(queues.balances));
        await(
                () -> {
                    SimpUser user = users.getUser(trader.toString());
                    if (user == null || user.getSessions().size() != 1) {
                        return false;
                    }
                    SimpSession session = user.getSessions().iterator().next();
                    return QUEUES.stream()
                            .allMatch(queue -> subscribers("/queue/" + queue + "-user" + session.getId()) == 1);
                },
                "the three subscriptions of " + trader);
        return queues;
    }

    private int subscribers(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        return broker.getSubscriptionRegistry().findSubscriptions(probe).values().stream()
                .mapToInt(List::size)
                .sum();
    }

    @SuppressWarnings("unchecked") // T-008: a JSON object is read as a raw map
    private Map<String, Object> balance(Queues queues, String asset) {
        return queues.balances.stream()
                .map(body -> (Map<String, Object>) JsonPath.read(body, "$"))
                .filter(row -> asset.equals(row.get("asset")))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no balance of " + asset + " pushed: " + queues.balances));
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    /** The USDT an opening grants, as configured. */
    private BigDecimal openingUsdt() {
        return grants.spotGrant().get("USDT");
    }

    private String baseAsset() {
        return sql.sql(
                        "select c.symbol from coin c join crypto_pair p on p.base_coin_id = c.coin_id where p.pair_id = ?")
                .param(pair)
                .query(String.class)
                .single();
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

    /** A Trader with an opened paper account. */
    private UUID trader() throws Exception {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@tr08.invalid", now, now).update();
        traders.add(id);
        as(id, post("/api/v1/paper/account"), null).andExpect(status().isCreated());
        return id;
    }

    private ResultActions place(UUID trader, String body) throws Exception {
        return as(trader, post("/api/v1/paper/orders"), body);
    }

    private ResultActions as(
            UUID trader,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(trader));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String id(ResultActions placed) throws Exception {
        return JsonPath.read(placed.andReturn().getResponse().getContentAsString(), "$.orderId");
    }

    private String market(String side, String quantity) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side
                + "\", \"type\": \"MARKET\", \"quantity\": " + quantity + "}";
    }

    private String limit(String side, String price, String quantity) {
        return "{\"pairId\": \"" + pair + "\", \"market\": \"SPOT\", \"side\": \"" + side + "\", \"type\": \"LIMIT\","
                + " \"price\": " + price + ", \"quantity\": " + quantity + "}";
    }

    /** Streams a Spot ticker of the test's pair into the cache, newer than every earlier one. */
    private void price(String last) {
        Instant at = Instant.now().plusMillis(TICKS.incrementAndGet());
        BigDecimal value = new BigDecimal(last);
        prices.record(
                MarketType.SPOT,
                new TickerMessage(
                        symbol,
                        value,
                        value,
                        value,
                        value,
                        value,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        at));
        prices.flush();
    }

    private String bearer(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, UserRole.TRADER.name())
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }

    private record Client(StompSession session, StompRecorder recorder) {}

    /** The messages of a Trader's three queues, in the order received. */
    private static final class Queues {
        private final List<String> orders = new CopyOnWriteArrayList<>();
        private final List<String> fills = new CopyOnWriteArrayList<>();
        private final List<String> balances = new CopyOnWriteArrayList<>();

        StompFrameHandler inbox(List<String> into) {
            return new Inbox(into);
        }
    }

    private static final class Inbox implements StompFrameHandler {

        private final List<String> payloads;

        Inbox() {
            this(new CopyOnWriteArrayList<>());
        }

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
