package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.client.ScriptedWebSocketClient.FakeWebSocket;
import com.cryptopilot.support.MutableTestClock;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ends of a connection whose order a real network decides — a handshake that completes after the shard was
 * closed, an error instead of a close, the old connection of a renewal ending after its replacement — each made to
 * happen exactly once, in a known order, on a scripted client, virtual time and a fixed clock. {@link
 * BinanceStreamShardTest} proves the same shard against a real socket; there, which of these paths a run takes
 * depends on thread timing.
 *
 * <p>Rule: NSF-03; TECHNICAL_DESIGN 7.1 steps 1, 2 and 6.
 *
 * <p>Reference: Meszaros, G. (2007). <i>xUnit Test Patterns</i>. Addison-Wesley, "Erratic Test" (a result that
 * depends on thread timing) and "Test Double" (a stand-in the test controls).
 */
class BinanceStreamShardScriptedTest {

    private static final Instant AT = Instant.parse("2026-09-24T10:00:00Z");
    private static final List<String> STREAMS = List.of("btcusdt@kline_1h", "btcusdt@markPrice@1s");
    private static final Duration RENEW_AFTER = Duration.ofMillis(300);

    private final List<Boolean> openings = new CopyOnWriteArrayList<>();
    private final ScriptedWebSocketClient http = new ScriptedWebSocketClient();
    private final MutableTestClock clock = new MutableTestClock(AT);
    private final ManualScheduler time = new ManualScheduler(clock);
    private final BinanceStreamShard shard = new BinanceStreamShard(
            "TEST-0",
            URI.create("ws://127.0.0.1:1"),
            STREAMS,
            http,
            new BinanceStreamParser(JsonMapper.builder().build()),
            time,
            BinanceStreamShardTest.properties(RENEW_AFTER, Duration.ofHours(1)),
            new ReconnectBackoff(Duration.ofMillis(20), Duration.ofMillis(100), 0, () -> 0.0),
            clock,
            new BinanceStreamShard.Listener() {
                @Override
                public void onMessage(StreamMessage message) {}

                @Override
                public void onConnected(BinanceStreamShard connected, boolean afterLoss) {
                    openings.add(afterLoss);
                }
            });

    @AfterEach
    void closeTheShard() {
        shard.close();
    }

    /** A handshake that completes after the shard was closed is aborted at once and never reported. */
    @Test
    void NSF03_aHandshakeCompletingAfterClose_isAbortedAndNeverReported() {
        shard.start();
        shard.close();

        FakeWebSocket late = http.attempt(0).open();

        assertThat(late.aborted()).isTrue();
        assertThat(shard.isConnected()).isFalse();
        assertThat(openings).isEmpty();
        time.advance(Duration.ofDays(1));
        assertThat(http.attempts()).hasSize(1);
    }

    /** A connection the exchange closes before its handshake reaches the shard is a loss, reconnected as one. */
    @Test
    void NSF03_aConnectionClosedBeforeItsHandshakeArrives_isALoss_andIsReconnected() {
        shard.start();
        ScriptedWebSocketClient.Attempt first = http.attempt(0);

        first.listener().onClose(new FakeWebSocket(), 1006, "");
        first.open();

        assertThat(shard.isConnected()).isFalse();
        assertThat(openings).isEmpty();
        time.advance(Duration.ofMillis(20));
        http.attempt(1).open();
        assertThat(openings).containsExactly(true);
    }

    /** An error on the open connection is a loss: the shard reconnects after the back-off and says so. */
    @Test
    void NSF03_anErrorOnTheOpenConnection_isALoss_andIsReconnected() {
        shard.start();
        FakeWebSocket first = http.attempt(0).open();

        http.attempt(0).listener().onError(first, new IOException("connection reset"));

        assertThat(shard.isConnected()).isFalse();
        assertThat(http.attempts()).hasSize(1);
        time.advance(Duration.ofMillis(20));
        http.attempt(1).open();
        assertThat(shard.isConnected()).isTrue();
        assertThat(openings).containsExactly(false, true);
    }

    /** The old connection of a renewal ending after its replacement opened is let go: no loss, no reconnect. */
    @Test
    void NSF03_theEndOfAConnectionReplacedByARenewal_isIgnored() {
        shard.start();
        FakeWebSocket old = http.attempt(0).open();
        time.advance(RENEW_AFTER);
        http.attempt(1).open();
        assertThat(old.closeCode()).isEqualTo(1000);

        http.attempt(0).listener().onClose(old, 1000, "renewed");
        http.attempt(0).listener().onError(old, new IOException("late error"));

        assertThat(shard.isConnected()).isTrue();
        assertThat(http.attempts()).hasSize(2);
        assertThat(openings).containsExactly(false, false);
    }

    /** A connection that ends after the shard was closed starts nothing. */
    @Test
    void NSF03_anEndAfterClose_startsNothing() {
        shard.start();
        FakeWebSocket open = http.attempt(0).open();
        shard.close();
        assertThat(open.closeCode()).isEqualTo(1000);

        http.attempt(0).listener().onClose(open, 1006, "");

        time.advance(Duration.ofDays(1));
        assertThat(http.attempts()).hasSize(1);
        assertThat(openings).containsExactly(false);
    }
}
