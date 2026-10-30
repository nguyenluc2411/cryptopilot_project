package com.cryptopilot.support;

import static com.cryptopilot.market.client.StubStreamServer.await;
import static com.cryptopilot.market.client.StubStreamServer.signal;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;

/**
 * The session handler of a test's STOMP client: it records the message of every ERROR frame and every failure the
 * session reports (a lost connection, a frame it could not handle), and wakes the tests' {@code await} on each.
 *
 * <p>{@link #awaitRefused(String)} is how a test sees a refused CONNECT, SUBSCRIBE or SEND. The server answers it with
 * an ERROR frame and closes the session at once; when another send to that session still holds the flush lock of
 * Spring's {@code ConcurrentWebSocketSessionDecorator}, the ERROR frame stays queued and the close discards it, so the
 * client sees only the connection lost. Both outcomes are a refusal: the server closes the connection, and any ERROR
 * frame that does arrive gives the reason.
 *
 * <p>Rule: D-50, D-126, TR-08; TECHNICAL_DESIGN 5.3.
 */
public final class StompRecorder extends StompSessionHandlerAdapter {

    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    /** The messages of the ERROR frames received, in order. */
    public List<String> errors() {
        return errors;
    }

    /** The failures the session reported, in order. */
    public List<Throwable> failures() {
        return failures;
    }

    /**
     * Waits for the server to end the refused session: the connection is lost, nothing else failed, and every ERROR
     * frame that arrived names {@code reason}.
     */
    public void awaitRefused(String reason) {
        // An ERROR frame that was written is read before the close; once the connection is lost, nothing else arrives.
        await(() -> !failures.isEmpty(), "the server to close the session");
        assertThat(failures).allSatisfy(failure -> assertThat(failure).isInstanceOf(ConnectionLostException.class));
        assertThat(errors).allSatisfy(message -> assertThat(message).contains(reason));
    }

    /** {@link #awaitRefused(String)}, and the client's session is closed. */
    public void awaitRefused(StompSession session, String reason) {
        awaitRefused(reason);
        assertThat(session.isConnected()).isFalse();
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
        errors.add(String.valueOf(headers.getFirst("message")));
        signal();
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
        failures.add(exception);
        signal();
    }

    @Override
    public void handleException(
            StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
        failures.add(exception);
        signal();
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
        return String.class;
    }
}
