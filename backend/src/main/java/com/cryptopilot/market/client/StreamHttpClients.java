package com.cryptopilot.market.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP client the stream connections open their sockets with, replaced when it can open none any more.
 *
 * <p>A JDK HTTP client whose selector thread has stopped, e.g. after the host aborted every connection at once, fails
 * each later handshake with "selector manager closed" and never recovers; the shards would retry on it forever and
 * the markets would stay paused (D-159). The first shard that meets such a failure swaps in a new client, which the
 * next attempt of every shard uses.
 *
 * <p>Rule: NSF-03.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 (a resource that
 * cannot recover is replaced rather than retried).
 */
public final class StreamHttpClients implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamHttpClients.class);
    private static final String SELECTOR_CLOSED = "selector manager closed";

    private final Supplier<HttpClient> factory;
    private final AtomicReference<HttpClient> current;
    private volatile boolean closed;

    /** Builds the first client now, and each replacement when one is needed. */
    public StreamHttpClients(Supplier<HttpClient> factory) {
        this.factory = factory;
        this.current = new AtomicReference<>(factory.get());
    }

    /** One client for good, never replaced: for tests that script it. */
    static StreamHttpClients fixed(HttpClient client) {
        return new StreamHttpClients(() -> client);
    }

    /** The client to open the next socket with. */
    public HttpClient current() {
        return current.get();
    }

    /**
     * Replaces {@code used} when this failure shows it can open no socket any more; a failure of the exchange or of
     * the network leaves it, and so does a client another shard has already replaced or one stopped by {@link #close}.
     */
    public void failed(HttpClient used, Throwable failure) {
        if (closed || current.get() != used || !dead(used, failure)) {
            return;
        }
        HttpClient next = factory.get();
        if (next == used) {
            return;
        }
        if (!closed && current.compareAndSet(used, next)) {
            log.warn("NSF-03 the stream HTTP client stopped ({}); a new one takes over", failure.toString());
            used.shutdownNow();
        } else {
            next.shutdownNow();
        }
    }

    private static boolean dead(HttpClient used, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException
                    && String.valueOf(cause.getMessage()).contains(SELECTOR_CLOSED)) {
                return true;
            }
        }
        try {
            return used.isTerminated();
        } catch (UnsupportedOperationException notKnown) {
            return false;
        }
    }

    /** Stops the current client; open sockets are aborted with it. */
    @Override
    public void close() {
        closed = true;
        current.get().shutdownNow();
    }
}
