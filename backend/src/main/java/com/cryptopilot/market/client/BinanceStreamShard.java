package com.cryptopilot.market.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One combined-stream connection to the exchange, kept open for as long as the shard lives.
 *
 * <h2>What it guarantees</h2>
 *
 * <ul>
 *   <li><b>Reconnect with back-off.</b> A closed, failed or refused connection is opened again after the
 *       {@link ReconnectBackoff} wait — 1 s, 2 s, 4 s … up to 60 s — which starts from 1 s again once a
 *       connection has opened (NSF-03).
 *   <li><b>Renewal before the 24-hour limit.</b> After {@code renewAfter} (23 h) a second connection is opened
 *       and the first is closed only once the second is up, so a planned renewal loses no message.
 *   <li><b>A silent connection is a dead one.</b> Every shard carries a once-a-second ticker or mark price, so
 *       no text for {@code idleTimeout} means a half-open connection the exchange will never close for us; it
 *       is aborted and reconnected.
 *   <li><b>An attempt that never answers is given up.</b> The client's own connect timeout runs on its selector
 *       thread and covers no upgrade answer, so a handshake in flight when that selector stopped, or one a host
 *       accepted and never answered, stays pending for good. An attempt unanswered for {@code connectTimeout}
 *       plus {@code idleTimeout} counts as failed and is retried (on a dead client the retry fails at once, which
 *       replaces it), and its late answer is let go. A failure while the client is replaced, or a refusal
 *       thrown at once, still schedules the next attempt: the shard always has a connection, an attempt in
 *       flight or a retry scheduled.
 *   <li><b>Pings are answered.</b> The JDK {@link WebSocket} replies to every ping with a pong carrying the
 *       same payload by itself — Spot disconnects after a minute without one, futures after ten.
 *   <li><b>The reader is never blocked.</b> A frame is parsed and handed to the {@link Listener}, which must
 *       return at once (it queues); a malformed frame is logged and skipped, never ends the connection.
 * </ul>
 *
 * <p>The caller learns of every opening through {@link Listener#onConnected}, with whether it followed a loss:
 * after a loss, candles may have closed while nobody listened, and the caller has them backfilled (NSF-03: any
 * detected gap triggers NSF-02). A planned renewal overlaps its two connections and is not a loss.
 *
 * <p>Nothing here sleeps: every wait is a task on the given timer, whose thread only starts asynchronous work.
 *
 * <p>Rule: NSF-03; BR-09; TECHNICAL_DESIGN 7.1 steps 1, 2 and 6, 7.1.1.
 *
 * <p>Reference: Fette, I. &amp; Melnikov, A. (2011). <i>RFC 6455: The WebSocket Protocol</i>. IETF, §5.5.2–5.5.3
 * (a pong answers a ping with the same application data). Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.).
 * Pragmatic Bookshelf, ch. 4 (a connection with no traffic may be dead without either side being told).
 */
public final class BinanceStreamShard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BinanceStreamShard.class);

    /** Receives what the shard reads. Called on the HTTP client's threads; must not block. */
    public interface Listener {

        /** One message the exchange sent. */
        void onMessage(StreamMessage message);

        /**
         * A connection has opened.
         *
         * @param shard the shard
         * @param afterLoss whether a previous connection of this shard was lost before it, so that messages may
         *     have been missed; false for the first connection and for a planned renewal
         */
        void onConnected(BinanceStreamShard shard, boolean afterLoss);
    }

    private final String name;
    private final URI uri;
    private final List<String> streams;
    private final StreamHttpClients http;
    private final BinanceStreamParser parser;
    private final ScheduledExecutorService timer;
    private final ReconnectBackoff backoff;
    private final Duration connectTimeout;
    private final Duration renewAfter;
    private final Duration idleTimeout;
    private final Clock clock;
    private final Listener listener;

    private WebSocket current;
    private Frames currentFrames;
    private boolean closed;
    private boolean lostSinceLastOpen;
    private int attempts;
    private ScheduledFuture<?> pending;
    private ScheduledFuture<?> renewal;
    private ScheduledFuture<?> watchdog;
    private volatile Instant lastMessageAt;
    private final Set<Attempt> inFlight = new HashSet<>();

    /**
     * A connection attempt not answered yet; its answer counts only while it is still in flight. Told apart by
     * identity, not by value: two attempts on one client started at one instant are still two.
     */
    private static final class Attempt {
        private final HttpClient client;
        private final boolean renewing;
        private final Instant startedAt;

        Attempt(HttpClient client, boolean renewing, Instant startedAt) {
            this.client = client;
            this.renewing = renewing;
            this.startedAt = startedAt;
        }
    }

    /**
     * A shard, not yet connected.
     *
     * @param name a name for the logs, e.g. {@code SPOT-0}
     * @param base the stream host, with its route when it has one
     * @param streams the stream names, e.g. {@code btcusdt@kline_1h}; at most the configured number per connection
     */
    public BinanceStreamShard(
            String name,
            URI base,
            List<String> streams,
            HttpClient http,
            BinanceStreamParser parser,
            ScheduledExecutorService timer,
            BinanceStreamProperties properties,
            ReconnectBackoff backoff,
            Clock clock,
            Listener listener) {
        this(name, base, streams, StreamHttpClients.fixed(http), parser, timer, properties, backoff, clock, listener);
    }

    /** A shard, not yet connected, opening its sockets with whichever client {@code http} holds at each attempt. */
    public BinanceStreamShard(
            String name,
            URI base,
            List<String> streams,
            StreamHttpClients http,
            BinanceStreamParser parser,
            ScheduledExecutorService timer,
            BinanceStreamProperties properties,
            ReconnectBackoff backoff,
            Clock clock,
            Listener listener) {
        if (streams.isEmpty() || streams.size() > properties.maxStreamsPerConnection()) {
            throw new IllegalArgumentException(
                    "a shard carries 1 to " + properties.maxStreamsPerConnection() + " streams, not " + streams.size());
        }
        this.name = name;
        this.uri = URI.create(stripTrailingSlash(base.toString()) + "/stream?streams=" + String.join("/", streams));
        this.streams = List.copyOf(streams);
        this.http = http;
        this.parser = parser;
        this.timer = timer;
        this.backoff = backoff;
        this.connectTimeout = properties.connectTimeout();
        this.renewAfter = properties.renewAfter();
        this.idleTimeout = properties.idleTimeout();
        this.clock = clock;
        this.listener = listener;
    }

    /** The combined-stream address this shard connects to. */
    public URI uri() {
        return uri;
    }

    /** The streams this shard carries. */
    public List<String> streams() {
        return streams;
    }

    /** Whether a connection is open now. */
    public synchronized boolean isConnected() {
        return current != null;
    }

    /** Opens the first connection and starts watching for silence. */
    public synchronized void start() {
        if (closed || watchdog != null) {
            return;
        }
        long every = Math.max(1, idleTimeout.toMillis() / 2);
        watchdog = timer.scheduleWithFixedDelay(this::checkIdle, every, every, TimeUnit.MILLISECONDS);
        connect(false);
    }

    /** Closes the connection and stops every retry; the shard is not reused. */
    @Override
    public void close() {
        WebSocket open;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            inFlight.clear();
            cancel(pending);
            cancel(renewal);
            cancel(watchdog);
            open = current;
            current = null;
        }
        if (open != null) {
            open.sendClose(WebSocket.NORMAL_CLOSURE, "").whenComplete((ws, error) -> open.abort());
        }
        log.info("NSF-03 {} closed", name);
    }

    /**
     * RF-12 fault switch: ends the open connection as a network loss would, and holds the next attempt back for
     * {@code outage}; the shard then reconnects as after any loss, and reports that opening as following one, so the
     * caller backfills what was missed. A renewal or an attempt in flight is given up and its late answer let go, so
     * nothing ends the outage early. Closing the shard meanwhile stops the reconnection like any other.
     *
     * @param outage how long the connection stays down, zero or more
     * @return whether a connection was open and has been cut; false on a closed shard or one without a connection
     */
    public boolean simulateLoss(Duration outage) {
        if (outage.isNegative()) {
            throw new IllegalArgumentException("an outage is zero or longer, not " + outage);
        }
        WebSocket cut;
        synchronized (this) {
            if (closed || current == null) {
                return false;
            }
            cut = current;
            currentFrames.ended = true;
            current = null;
            lostSinceLastOpen = true;
            inFlight.clear();
            cancel(renewal);
            cancel(pending);
            attempts = 0;
            pending = timer.schedule(() -> connect(false), outage.toMillis(), TimeUnit.MILLISECONDS);
        }
        cut.abort();
        log.warn("NSF-03 {} cut by the fault switch (RF-12); reconnecting in {}", name, outage);
        return true;
    }

    private void connect(boolean renewing) {
        Frames frames = new Frames();
        HttpClient client = http.current();
        Attempt attempt = new Attempt(client, renewing, clock.instant());
        synchronized (this) {
            if (closed) {
                return;
            }
            inFlight.add(attempt);
        }
        CompletableFuture<WebSocket> handshake;
        try {
            handshake =
                    client.newWebSocketBuilder().connectTimeout(connectTimeout).buildAsync(uri, frames);
        } catch (RuntimeException refused) {
            handshake = CompletableFuture.failedFuture(refused);
        }
        handshake.whenComplete((socket, failure) -> {
            if (!answered(attempt)) {
                if (socket != null) {
                    socket.abort();
                }
                return;
            }
            if (failure != null) {
                attemptFailed(attempt, failure);
            } else {
                opened(socket, frames, renewing);
            }
        });
    }

    /** Takes the attempt out of those in flight; false when it was given up already. */
    private synchronized boolean answered(Attempt attempt) {
        return inFlight.remove(attempt);
    }

    /**
     * The next attempt is scheduled even when telling the clients of the failure fails, e.g. when a new client
     * cannot be built; nor does that failure reach the watchdog, whose later runs a throw would cancel.
     */
    private void attemptFailed(Attempt attempt, Throwable failure) {
        try {
            http.failed(attempt.client, failure);
        } catch (RuntimeException | InternalError notReplaced) {
            log.warn("NSF-03 {} could not replace its stream HTTP client ({})", name, notReplaced.toString());
        }
        failed(attempt.renewing, failure);
    }

    private void opened(WebSocket socket, Frames frames, boolean renewing) {
        WebSocket previous;
        boolean afterLoss;
        synchronized (this) {
            if (closed) {
                socket.abort();
                return;
            }
            if (frames.ended) {
                // The exchange closed it before the handshake's completion reached us: a loss like any other.
                failed(renewing, new IllegalStateException("closed as soon as it opened"));
                return;
            }
            previous = current;
            current = socket;
            currentFrames = frames;
            attempts = 0;
            afterLoss = lostSinceLastOpen && !renewing;
            lostSinceLastOpen = false;
            lastMessageAt = clock.instant();
            cancel(renewal);
            renewal = timer.schedule(() -> connect(true), renewAfter.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (previous != null) {
            previous.sendClose(WebSocket.NORMAL_CLOSURE, "renewed").whenComplete((ws, error) -> previous.abort());
        }
        log.info("NSF-03 {} connected ({} streams{})", name, streams.size(), renewing ? ", renewal" : "");
        listener.onConnected(this, afterLoss);
    }

    private void failed(boolean renewing, Throwable failure) {
        synchronized (this) {
            if (closed) {
                return;
            }
            Duration wait = backoff.delay(attempts++);
            log.warn("NSF-03 {} could not connect ({}); next attempt in {}", name, failure.toString(), wait);
            if (renewing && current != null) {
                renewal = timer.schedule(() -> connect(true), wait.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                lostSinceLastOpen = true;
                pending = timer.schedule(() -> connect(false), wait.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * A connection ended without being asked to. An old one replaced by a renewal is let go silently; one that
     * ends before {@link #opened} has registered it is caught there, by its {@link Frames#ended} flag.
     */
    private void lost(WebSocket socket, Frames frames, String why) {
        synchronized (this) {
            frames.ended = true;
            if (closed || socket != current) {
                return;
            }
            current = null;
            lostSinceLastOpen = true;
            cancel(renewal);
            Duration wait = backoff.delay(attempts++);
            log.warn("NSF-03 {} lost its connection ({}); reconnecting in {}", name, why, wait);
            pending = timer.schedule(() -> connect(false), wait.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void checkIdle() {
        giveUpUnanswered();
        WebSocket silent;
        Frames silentFrames;
        synchronized (this) {
            Instant last = lastMessageAt;
            if (closed || current == null || last == null || !clock.instant().isAfter(last.plus(idleTimeout))) {
                return;
            }
            silent = current;
            silentFrames = currentFrames;
        }
        silent.abort();
        lost(silent, silentFrames, "silent for more than " + idleTimeout);
    }

    private void giveUpUnanswered() {
        List<Attempt> expired = new ArrayList<>();
        synchronized (this) {
            if (closed) {
                return;
            }
            Instant now = clock.instant();
            Duration patience = connectTimeout.plus(idleTimeout);
            for (Attempt attempt : inFlight) {
                if (now.isAfter(attempt.startedAt.plus(patience))) {
                    expired.add(attempt);
                }
            }
            expired.forEach(inFlight::remove);
        }
        for (Attempt attempt : expired) {
            attemptFailed(
                    attempt,
                    new HttpConnectTimeoutException(
                            "no answer to the handshake for " + Duration.between(attempt.startedAt, clock.instant())));
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The frames of one connection: text is gathered until its last part, then parsed. */
    private final class Frames implements WebSocket.Listener {

        private final StringBuilder text = new StringBuilder();

        /** Set, under the shard's lock, once the connection has closed or failed. */
        private boolean ended;

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String frame = text.toString();
                text.setLength(0);
                lastMessageAt = clock.instant();
                try {
                    parser.parse(frame).ifPresent(listener::onMessage);
                } catch (IllegalArgumentException malformed) {
                    log.warn("NSF-03 {} skipped a malformed frame: {}", name, malformed.getMessage());
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            lost(socket, this, "closed by the exchange, " + statusCode + (reason.isEmpty() ? "" : " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            lost(socket, this, error.toString());
        }
    }
}
