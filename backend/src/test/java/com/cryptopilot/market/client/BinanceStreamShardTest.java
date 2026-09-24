package com.cryptopilot.market.client;

import static com.cryptopilot.market.client.StubStreamServer.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StubStreamServer.Connection;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * One stream connection against a local WebSocket server, through the real JDK client: the combined-stream
 * address, messages handed over in order, pings answered, and every way a connection ends — lost, closed by
 * the exchange, refused, silent, renewed, closed by us — leading to the right next step.
 *
 * <p>Rule: NSF-03 (reconnect with exponential back-off from 1 s up to 60 s; renewed before the 24-hour
 * lifetime); TECHNICAL_DESIGN 7.1 steps 1, 2 and 6, 7.1.1.
 */
class BinanceStreamShardTest {

    private static final Instant AT = Instant.parse("2026-09-24T10:00:00Z");
    private static final List<String> STREAMS = List.of("btcusdt@kline_1h", "btcusdt@markPrice@1s");

    private final List<StreamMessage> messages = new CopyOnWriteArrayList<>();
    private final List<Boolean> openings = new CopyOnWriteArrayList<>();
    private final BinanceStreamShard.Listener listener = new BinanceStreamShard.Listener() {
        @Override
        public void onMessage(StreamMessage message) {
            messages.add(message);
        }

        @Override
        public void onConnected(BinanceStreamShard shard, boolean afterLoss) {
            openings.add(afterLoss);
        }
    };

    private StubStreamServer server;
    private HttpClient http;
    private ScheduledExecutorService timer;
    private BinanceStreamShard shard;

    @BeforeEach
    void startTheStandIn() throws Exception {
        server = new StubStreamServer();
        http = HttpClient.newHttpClient();
        timer = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void stopEverything() throws Exception {
        if (shard != null) {
            shard.close();
        }
        timer.shutdownNow();
        http.shutdownNow();
        server.close();
    }

    /** 7.1 step 1: one combined connection, the route of the configured host kept in front of it. */
    @Test
    void NSF03_theShard_connectsToTheCombinedStreamUnderTheConfiguredRoute() {
        shard = shard(properties(Duration.ofHours(23), Duration.ofHours(1)), server.baseUrl("/market/"));

        shard.start();
        server.awaitConnection(1);

        assertThat(server.paths()).containsExactly("/market/stream?streams=btcusdt@kline_1h/btcusdt@markPrice@1s");
        assertThat(shard.uri().toString()).endsWith("/market/stream?streams=btcusdt@kline_1h/btcusdt@markPrice@1s");
        assertThat(shard.streams()).isEqualTo(STREAMS);
        await(shard::isConnected, "the shard to see its connection");
        assertThat(openings).containsExactly(false);
    }

    /** Messages arrive in order; a malformed frame or an unknown event is skipped and the connection stays. */
    @Test
    void NSF03_messages_arriveInOrder_andABadFrameIsSkipped() {
        shard = started();
        Connection connection = server.awaitConnection(1);

        connection.send(StreamFrames.kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, false));
        connection.send("{\"stream\":\"x\",\"data\":{\"e\":\"kline\",\"k\":{}}}");
        connection.send("{\"stream\":\"x\",\"data\":{\"e\":\"serverShutdown\",\"E\":1}}");
        connection.send(StreamFrames.markPrice("BTCUSDT", AT));
        connection.send(StreamFrames.kline("BTCUSDT", MarketInterval.ONE_HOUR, AT, true));

        await(() -> messages.size() == 3, "three messages");
        assertThat(messages.get(0)).isInstanceOfSatisfying(KlineMessage.class, k -> assertThat(k.closed())
                .isFalse());
        assertThat(messages.get(1)).isInstanceOf(MarkPriceMessage.class);
        assertThat(messages.get(2)).isInstanceOfSatisfying(KlineMessage.class, k -> assertThat(k.closed())
                .isTrue());
        assertThat(server.connections()).hasSize(1);
    }

    /** A frame larger than one read arrives in parts and is put back together. */
    @Test
    void NSF03_aLargeFrame_isReassembled() {
        shard = started();
        Connection connection = server.awaitConnection(1);
        String padding = "x".repeat(70_000);

        connection.send(
                StreamFrames.markPrice("BTCUSDT", AT).replace("\"st\":1", "\"st\":1,\"pad\":\"" + padding + "\""));

        await(() -> messages.size() == 1, "the large message");
    }

    /** Spot disconnects after a minute without a pong; the JDK answers every ping with its payload. */
    @Test
    void NSF03_aPing_isAnsweredWithAPongCarryingItsPayload() {
        shard = started();
        Connection connection = server.awaitConnection(1);

        connection.ping("1790259540026");

        await(() -> connection.pongs().contains("1790259540026"), "the pong");
    }

    /** A dropped connection is opened again after the back-off, and reported as following a loss. */
    @Test
    void NSF03_aDroppedConnection_isReopened_andReportedAsALoss() {
        shard = started();
        Connection first = server.awaitConnection(1);
        await(shard::isConnected, "the first connection");

        first.drop();
        Connection second = server.awaitConnection(2);
        await(() -> openings.size() == 2, "the second opening");

        assertThat(openings).containsExactly(false, true);
        second.send(StreamFrames.markPrice("BTCUSDT", AT));
        await(() -> messages.size() == 1, "a message on the new connection");
    }

    /** The exchange closes at the 24-hour mark (or before a shutdown): the shard comes back by itself. */
    @Test
    void NSF03_aCloseFromTheExchange_isFollowedByANewConnection() {
        shard = started();
        server.awaitConnection(1).closeWith(1001);

        server.awaitConnection(2);
        await(() -> openings.size() == 2, "the second opening");

        assertThat(openings).containsExactly(false, true);
    }

    /** Refused handshakes are retried with the back-off until one succeeds. */
    @Test
    void NSF03_refusedHandshakes_areRetriedUntilOneSucceeds() {
        server.refuseNext(3);
        shard = started();

        server.awaitConnection(1);
        await(() -> openings.size() == 1, "the opening");

        assertThat(server.paths()).hasSize(4);
        assertThat(openings).containsExactly(true);
    }

    /** 7.1 step 6: renewed before 24 hours by a second connection; the first is closed once the second is up. */
    @Test
    void NSF03_renewal_opensTheNewConnectionBeforeClosingTheOld_andIsNotALoss() {
        shard = shard(properties(Duration.ofMillis(300), Duration.ofHours(1)), server.baseUrl(""));
        shard.start();
        Connection first = server.awaitConnection(1);

        Connection second = server.awaitConnection(2);
        await(() -> first.closeCode() != null, "the old connection to be closed");

        assertThat(first.closeCode()).isEqualTo(1000);
        await(() -> openings.size() == 2, "the renewal");
        assertThat(openings).containsExactly(false, false);
        second.send(StreamFrames.markPrice("BTCUSDT", AT));
        await(() -> messages.size() == 1, "a message on the renewed connection");
        assertThat(shard.isConnected()).isTrue();
    }

    /** A renewal that cannot connect keeps the working connection and tries again. */
    @Test
    void NSF03_aFailedRenewal_keepsTheOldConnection_andTriesAgain() {
        shard = shard(properties(Duration.ofMillis(200), Duration.ofHours(1)), server.baseUrl(""));
        shard.start();
        Connection first = server.awaitConnection(1);
        server.refuseNext(1);

        server.awaitConnection(2);

        assertThat(server.paths()).hasSize(3);
        await(() -> first.closeCode() != null, "the old connection to be closed after the renewal");
        assertThat(openings).containsExactly(false, false);
    }

    /** A connection that goes silent is presumed dead, aborted and replaced. */
    @Test
    void NSF03_aSilentConnection_isReplaced() {
        shard = shard(properties(Duration.ofHours(23), Duration.ofMillis(200)), server.baseUrl(""));
        shard.start();
        server.awaitConnection(1);

        server.awaitConnection(2);
        await(() -> openings.size() == 2, "the replacement");

        assertThat(openings).containsExactly(false, true);
    }

    /** Messages keep a connection alive past the idle timeout. */
    @Test
    void NSF03_aConnectionThatKeepsTalking_isKept() throws Exception {
        shard = shard(properties(Duration.ofHours(23), Duration.ofMillis(300)), server.baseUrl(""));
        shard.start();
        Connection connection = server.awaitConnection(1);

        for (int i = 0; i < 8; i++) {
            connection.send(StreamFrames.markPrice("BTCUSDT", AT));
            Thread.sleep(100);
        }

        assertThat(server.connections()).hasSize(1);
    }

    /** Closed by us: a polite close, and no reconnection afterwards. */
    @Test
    void NSF03_closingTheShard_closesPolitely_andNeverReconnects() throws Exception {
        shard = started();
        Connection connection = server.awaitConnection(1);
        await(shard::isConnected, "the connection");

        shard.close();
        shard.close();

        await(() -> connection.closeCode() != null, "the close frame");
        assertThat(connection.closeCode()).isEqualTo(1000);
        assertThat(shard.isConnected()).isFalse();
        Thread.sleep(200);
        assertThat(server.connections()).hasSize(1);
        shard.start();
        Thread.sleep(100);
        assertThat(server.paths()).hasSize(1);
    }

    /** A shard closed while its connection is still opening drops that connection when it arrives. */
    @Test
    void NSF03_aConnectionThatOpensAfterClose_isAbandoned() {
        shard = started();
        shard.close();

        await(() -> server.connections().size() <= 1 && server.paths().size() == 1, "the handshake");
        assertThat(openings).isEmpty();
    }

    /** A lost connection after close is not reconnected, and starting twice opens one connection. */
    @Test
    void NSF03_startingTwice_opensOneConnection() throws Exception {
        shard = started();
        shard.start();
        server.awaitConnection(1);
        Thread.sleep(100);

        assertThat(server.paths()).hasSize(1);
    }

    @Test
    void NSF03_aShard_carriesOneToTheMaximumStreams() {
        BinanceStreamProperties properties = properties(Duration.ofHours(23), Duration.ofHours(1));
        List<String> tooMany =
                IntStream.range(0, 101).mapToObj(i -> "s" + i + "@ticker").toList();

        assertThatThrownBy(() -> shard(properties, server.baseUrl(""), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> shard(properties, server.baseUrl(""), tooMany)).hasMessageContaining("not 101");
    }

    private BinanceStreamShard started() {
        BinanceStreamShard started = shard(properties(Duration.ofHours(23), Duration.ofHours(1)), server.baseUrl(""));
        started.start();
        return started;
    }

    private BinanceStreamShard shard(BinanceStreamProperties properties, URI base) {
        return shard(properties, base, STREAMS);
    }

    private BinanceStreamShard shard(BinanceStreamProperties properties, URI base, List<String> streams) {
        return new BinanceStreamShard(
                "TEST-0",
                base,
                streams,
                http,
                new BinanceStreamParser(JsonMapper.builder().build()),
                timer,
                properties,
                new ReconnectBackoff(Duration.ofMillis(20), Duration.ofMillis(100), 0, () -> 0.0),
                Clock.systemUTC(),
                listener);
    }

    static BinanceStreamProperties properties(Duration renewAfter, Duration idleTimeout) {
        return new BinanceStreamProperties(
                true,
                URI.create("ws://127.0.0.1:1"),
                URI.create("ws://127.0.0.1:1/market"),
                100,
                Duration.ofSeconds(2),
                new BinanceStreamProperties.Reconnect(Duration.ofMillis(20), Duration.ofMillis(100), 0),
                renewAfter,
                idleTimeout,
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                Duration.ofMinutes(2),
                2,
                100);
    }
}
