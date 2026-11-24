package com.cryptopilot.market.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.MutableTestClock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * NSF-03: a stream HTTP client that can open no socket any more is replaced, so the connections recover instead of
 * retrying on it for good (and the markets stay paused, D-159).
 */
class StreamHttpClientsTest {

    private static final Instant AT = Instant.parse("2026-11-24T08:31:51Z");
    private static final List<String> STREAMS = List.of("btcusdt@kline_1h");

    private static CompletionException selectorClosed() {
        return new CompletionException(new IOException("selector manager closed"));
    }

    /** Hands out the scripted clients in turn, and counts how many were built. */
    private static final class Clients {
        final Deque<ScriptedWebSocketClient> left = new ArrayDeque<>();
        int built;

        Clients(ScriptedWebSocketClient... clients) {
            left.addAll(List.of(clients));
        }

        HttpClient next() {
            built++;
            return left.removeFirst();
        }
    }

    @Test
    void NSF03_aClientWhoseSelectorClosed_isReplaced_andOnlyOnce() {
        ScriptedWebSocketClient first = new ScriptedWebSocketClient();
        ScriptedWebSocketClient second = new ScriptedWebSocketClient();
        Clients clients = new Clients(first, second);
        StreamHttpClients http = new StreamHttpClients(clients::next);

        http.failed(first, selectorClosed());
        // A second shard meeting the same failure on the old client builds no third.
        http.failed(first, selectorClosed());

        assertThat(http.current()).isSameAs(second);
        assertThat(clients.built).isEqualTo(2);
    }

    @Test
    void NSF03_aFailureOfTheExchangeOrNetwork_keepsTheClient() {
        ScriptedWebSocketClient first = new ScriptedWebSocketClient();
        Clients clients = new Clients(first, new ScriptedWebSocketClient());
        StreamHttpClients http = new StreamHttpClients(clients::next);

        for (Throwable failure : List.of(
                new CompletionException(new IOException("connection reset")),
                new IOException("closed by the exchange, 1006"),
                new IllegalStateException("closed as soon as it opened"),
                new IOException((String) null))) {
            http.failed(first, failure);
        }

        assertThat(http.current()).isSameAs(first);
        assertThat(clients.built).isEqualTo(1);
    }

    /** Stopped on purpose, at shutdown: the failures of its last attempts build no client that nothing would stop. */
    @Test
    void NSF03_afterClose_aFailureBuildsNoNewClient() {
        ScriptedWebSocketClient first = new ScriptedWebSocketClient();
        Clients clients = new Clients(first, new ScriptedWebSocketClient());
        StreamHttpClients http = new StreamHttpClients(clients::next);

        http.close();
        http.failed(first, selectorClosed());

        assertThat(http.current()).isSameAs(first);
        assertThat(clients.built).isEqualTo(1);
    }

    /** The shard's next attempt after the failure goes to the new client and connects. */
    @Test
    void NSF03_aShardOnAStoppedClient_reconnectsOnTheNewOne() {
        ScriptedWebSocketClient first = new ScriptedWebSocketClient();
        ScriptedWebSocketClient second = new ScriptedWebSocketClient();
        Clients clients = new Clients(first, second);
        MutableTestClock clock = new MutableTestClock(AT);
        ManualScheduler time = new ManualScheduler(clock);
        List<Boolean> openings = new CopyOnWriteArrayList<>();
        try (BinanceStreamShard shard = new BinanceStreamShard(
                "TEST-0",
                URI.create("ws://127.0.0.1:1"),
                STREAMS,
                new StreamHttpClients(clients::next),
                new BinanceStreamParser(JsonMapper.builder().build()),
                time,
                BinanceStreamShardTest.properties(Duration.ofHours(1), Duration.ofHours(1)),
                new ReconnectBackoff(Duration.ofMillis(20), Duration.ofMillis(100), 0, () -> 0.0),
                clock,
                new BinanceStreamShard.Listener() {
                    @Override
                    public void onMessage(StreamMessage message) {}

                    @Override
                    public void onConnected(BinanceStreamShard connected, boolean afterLoss) {
                        openings.add(afterLoss);
                    }
                })) {
            shard.start();
            first.attempt(0).handshake().completeExceptionally(selectorClosed());
            time.advance(Duration.ofMillis(20));

            assertThat(first.attempts()).hasSize(1);
            second.attempt(0).open();
            assertThat(shard.isConnected()).isTrue();
            assertThat(openings).containsExactly(true);
        }
    }
}
