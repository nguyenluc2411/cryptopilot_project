package com.cryptopilot.market.client;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A local HTTP server standing in for the exchange, so that no test ever calls Binance: a CI runner may
 * be geo-blocked (451), and a test that depends on a live market is not deterministic.
 *
 * <p>Built on the JDK's own {@code com.sun.net.httpserver}, which is enough for canned answers with a
 * status, headers, a body and a delay, and needs no library. Answers are queued per path and served in
 * order; the last one repeats, so "always 500" is one answer and "500, 500, then 200" is three.
 * Every request's path and query is recorded, so a test can assert what was asked and how often.
 */
public final class StubExchange implements AutoCloseable {

    /** One canned answer. */
    public record Answer(int status, Map<String, String> headers, String body, boolean withheld) {

        public static Answer ok(String body) {
            return new Answer(200, Map.of(), body, false);
        }

        public static Answer status(int status) {
            return new Answer(status, Map.of(), "{\"code\":-1,\"msg\":\"stub\"}", false);
        }

        public Answer withHeader(String name, String value) {
            Map<String, String> all = new HashMap<>(headers);
            all.put(name, value);
            return new Answer(status, Map.copyOf(all), body, withheld);
        }

        /**
         * This answer, held back until {@link StubExchange#release()} or {@link StubExchange#close()}: a server that
         * does not answer, for as long as the test needs, without any timer of its own.
         */
        public Answer held() {
            return new Answer(status, headers, body, true);
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Answer>> answers = new ConcurrentHashMap<>();
    private final Map<String, Function<URI, Answer>> responders = new ConcurrentHashMap<>();
    private final List<URI> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    public StubExchange() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI());
            Function<URI, Answer> responder =
                    responders.get(exchange.getRequestURI().getPath());
            Answer answer = responder != null
                    ? responder.apply(exchange.getRequestURI())
                    : next(exchange.getRequestURI().getPath());
            if (answer.withheld()) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            answer.headers()
                    .forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(answer.status(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException clientGaveUp) {
                // The client timed out and closed the connection; nothing to answer.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    /** Queues answers for a path; the last one repeats once the others are used. */
    public StubExchange on(String path, Answer... inOrder) {
        responders.remove(path);
        answers.put(path, new ArrayDeque<>(List.of(inOrder)));
        return this;
    }

    /** Answers a path from the request itself — its query string — instead of from a queue. */
    public StubExchange respond(String path, Function<URI, Answer> responder) {
        answers.remove(path);
        responders.put(path, responder);
        return this;
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** How many requests reached this path. */
    public long hits(String path) {
        return requests.stream().filter(uri -> uri.getPath().equals(path)).count();
    }

    /** Every request, in the order received. */
    public List<URI> requests() {
        return new ArrayList<>(requests);
    }

    private Answer next(String path) {
        Deque<Answer> queue = answers.get(path);
        if (queue == null) {
            return Answer.status(404);
        }
        synchronized (queue) {
            return queue.size() > 1 ? queue.poll() : queue.peek();
        }
    }

    /** Lets every held answer go. */
    public void release() {
        release.countDown();
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
    }
}
