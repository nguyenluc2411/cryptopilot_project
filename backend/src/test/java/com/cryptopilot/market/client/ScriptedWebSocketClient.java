package com.cryptopilot.market.client;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A client whose WebSocket connections the test opens, fails and ends by hand. Each attempt is recorded with the
 * listener the shard gave it and a future the test completes, so an event that a real network delivers at a moment
 * of its own — a handshake finishing after a close, an error instead of a close — happens exactly when the test says.
 *
 * <p>Reference: Meszaros, G. (2007). <i>xUnit Test Patterns</i>. Addison-Wesley, "Test Double" and "Erratic Test".
 */
final class ScriptedWebSocketClient extends HttpClient {

    /** One connection attempt: what the shard asked for and how it will end. */
    record Attempt(URI uri, WebSocket.Listener listener, CompletableFuture<WebSocket> handshake) {

        /** Completes the handshake with a new open socket, which is returned. */
        FakeWebSocket open() {
            FakeWebSocket socket = new FakeWebSocket();
            handshake.complete(socket);
            return socket;
        }
    }

    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();

    /** The attempts so far, oldest first. */
    List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    /** The attempt with this index; fails the test when it was never made. */
    Attempt attempt(int index) {
        if (index >= attempts.size()) {
            throw new AssertionError("attempt " + index + " was not made; attempts: " + attempts.size());
        }
        return attempts.get(index);
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        return new WebSocket.Builder() {
            @Override
            public WebSocket.Builder header(String name, String value) {
                return this;
            }

            @Override
            public WebSocket.Builder connectTimeout(Duration timeout) {
                return this;
            }

            @Override
            public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
                return this;
            }

            @Override
            public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
                CompletableFuture<WebSocket> handshake = new CompletableFuture<>();
                attempts.add(new Attempt(uri, listener, handshake));
                return handshake;
            }
        };
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        throw new UnsupportedOperationException();
    }

    /** An open connection that records what the shard did to it. */
    static final class FakeWebSocket implements WebSocket {

        private volatile Integer closeCode;
        private volatile boolean aborted;

        /** The status of the close the shard sent, or null. */
        Integer closeCode() {
            return closeCode;
        }

        /** Whether the shard aborted it. */
        boolean aborted() {
            return aborted;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeCode = statusCode;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {}

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return closeCode != null || aborted;
        }

        @Override
        public boolean isInputClosed() {
            return aborted;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
