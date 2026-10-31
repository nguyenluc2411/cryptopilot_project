package com.cryptopilot.trading.controller;

import static com.cryptopilot.market.client.StubStreamServer.await;
import static com.cryptopilot.market.client.StubStreamServer.signal;
import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.support.StompRecorder;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.trading.calculator.warning.PlanFixture;
import com.cryptopilot.trading.entity.TradingPlan;
import com.cryptopilot.trading.model.Excursion;
import com.cryptopilot.trading.model.Exit;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PlanDetails;
import com.cryptopilot.trading.model.PositionExit;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.TrackedPosition;
import com.cryptopilot.trading.model.enums.AnalysisTimeframe;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import com.cryptopilot.trading.repository.TradingPlanRepository;
import com.cryptopilot.trading.service.MatchingService;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * The position queue end to end: real STOMP clients on {@code /ws}, signed in with an access token in the
 * {@code CONNECT} frame. When a position closes, its owner receives the change on {@code /user/queue/positions};
 * another Trader subscribed to the same queue receives nothing, another user's queue cannot be named, and a session
 * without a signed-in user cannot subscribe. Every wait is on an event, never on a length of time.
 *
 * <p>Rule: BR-35, UC-20; TECHNICAL_DESIGN 9; Q-57, Q-75; D-123.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class PositionUserQueueTest {

    private static final String POSITIONS = "/user/queue/positions";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MatchingService matching;

    @Autowired
    private TradingPlanRepository plans;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    @Autowired
    private SimpUserRegistry users;

    private final List<UUID> traders = new ArrayList<>();
    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private UUID pair;
    private String symbol;

    @BeforeEach
    void insertPair() {
        symbol = "PU" + Long.toString(System.nanoTime() % 1_000_000_000L) + "USDT";
        pair = new MarketTestData(sql, Instant.now()).pair(symbol, true, false, "TRADING", null, 0);
        sql.sql("""
                        update crypto_pair set spot_tick_size = 0.01, spot_step_size = 0.001, spot_min_notional = 5
                         where pair_id = ?""").param(pair).update();
    }

    @AfterEach
    void removeRows() {
        try {
            for (UUID trader : traders) {
                sql.sql("delete from notification where user_id = ?")
                        .param(trader)
                        .update();
                sql.sql("delete from trading_journal where user_id = ?")
                        .param(trader)
                        .update();
                sql.sql("delete from trading_plan where user_id = ?")
                        .param(trader)
                        .update();
                sql.sql("delete from user_account where user_id = ?")
                        .param(trader)
                        .update();
            }
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

    /** UC-20: the owner's session receives the closed position; another Trader's session on the same queue does not. */
    @Test
    void UC20_aClosedPosition_isPushedToItsOwnerOnly() throws Exception {
        UUID owner = trader();
        UUID other = trader();
        List<String> ownerInbox = subscribed(owner);
        List<String> otherInbox = subscribed(other);
        TrackedPosition position = openPosition(owner);

        assertThat(matching.close(new PositionExit(
                        position,
                        new Exit(position.planId(), CloseReason.TAKE_PROFIT, new BigDecimal("115"), Instant.now()),
                        Excursion.at(position.entryPrice()))))
                .isTrue();

        await(() -> !ownerInbox.isEmpty(), "the owner's position update");
        String body = ownerInbox.getFirst();
        assertThat((String) JsonPath.read(body, "$.positionId"))
                .isEqualTo(position.positionId().toString());
        assertThat((String) JsonPath.read(body, "$.planId"))
                .isEqualTo(position.planId().toString());
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(body, "$.closeReason")).isEqualTo("TAKE_PROFIT");
        assertThat((String) JsonPath.read(body, "$.symbol")).isEqualTo(symbol);
        assertThat(new BigDecimal(String.valueOf(JsonPath.<Object>read(body, "$.realizedPnl"))))
                .isEqualByComparingTo("30");
        assertThat((Boolean) JsonPath.read(body, "$.provisional")).isFalse();
        // MSG23 is written after the same commit: once it is there, any push to the other Trader would be too.
        await(() -> notifications(owner) == 1, "the owner's MSG23");
        assertThat(otherInbox).isEmpty();
    }

    /** Q-75: a session without a signed-in user cannot subscribe to the position queue; it is refused and closed. */
    @Test
    void UC20_aGuest_cannotSubscribeToThePositionQueue() throws Exception {
        Client client = connect(null);

        client.session.subscribe(POSITIONS, new Inbox(new CopyOnWriteArrayList<>()));

        client.recorder().awaitRefused(client.session(), "sign in to subscribe");
    }

    /** Q-75: another user's position queue cannot be named, even by a signed-in Trader. */
    @Test
    void UC20_anotherUsersPositionQueue_cannotBeNamed() throws Exception {
        UUID owner = trader();
        UUID other = trader();
        Client client = connect(bearer(other));

        client.session.subscribe("/user/" + owner + "/queue/positions", new Inbox(new CopyOnWriteArrayList<>()));

        client.recorder().awaitRefused(client.session(), "not a public market topic");
    }

    /** A Spot LIMIT LONG of the Trader, 2 at 100 (SL 95, TP 115), filled by the engine: its OPEN position. */
    private TrackedPosition openPosition(UUID trader) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PlanFixture fixture = PlanFixture.spot().takeProfit("115");
        TradingPlan plan = inTransaction(() -> {
            TradingPlan draft = TradingPlan.draft(
                    trader,
                    pair,
                    fixture.build(),
                    new PlanDetails(
                            EntryType.LIMIT,
                            now.plus(Duration.ofDays(7)),
                            null,
                            null,
                            null,
                            AnalysisTimeframe.ONE_HOUR),
                    List.of());
            draft.activate(fixture.build(), List.of(), now);
            return plans.save(draft);
        });
        TrackedEntry entry = new TrackedEntry(
                plan.getId(),
                trader,
                plan.getMarket(),
                pair,
                plan.getDirection(),
                plan.getEntryType(),
                plan.getEntryPrice(),
                plan.getStopLoss(),
                plan.getTakeProfit(),
                null,
                now);
        return matching.fill(new Fill(entry, LiquidityRole.MAKER, new BigDecimal("100"), now))
                .orElseThrow();
    }

    /** A session of the Trader subscribed to the position queue, once the broker holds the subscription. */
    private List<String> subscribed(UUID trader) throws Exception {
        Client client = connect(bearer(trader));
        List<String> inbox = new CopyOnWriteArrayList<>();
        client.session.subscribe(POSITIONS, new Inbox(inbox));
        await(
                () -> {
                    SimpUser user = users.getUser(trader.toString());
                    if (user == null || user.getSessions().size() != 1) {
                        return false;
                    }
                    SimpSession session = user.getSessions().iterator().next();
                    return subscribers("/queue/positions-user" + session.getId()) == 1;
                },
                "the subscription of " + trader);
        return inbox;
    }

    private int subscribers(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        return broker.getSubscriptionRegistry().findSubscriptions(probe).values().stream()
                .mapToInt(List::size)
                .sum();
    }

    private long notifications(UUID trader) {
        return sql.sql("select count(*) from notification where user_id = ?")
                .param(trader)
                .query(Long.class)
                .single();
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@pq.invalid", now, now).update();
        traders.add(id);
        return id;
    }

    private Client connect(String bearer) throws Exception {
        StompRecorder recorder = new StompRecorder();
        StompHeaders connect = new StompHeaders();
        if (bearer != null) {
            connect.add(HttpHeaders.AUTHORIZATION, bearer);
        }
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new TextConverter());
        clients.add(stomp);
        StompSession session = stomp.connectAsync(
                        "ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect, recorder)
                .get(5, TimeUnit.SECONDS); // ends when the session connects; the bound only stops a hang
        sessions.add(session);
        return new Client(session, recorder);
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

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
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
