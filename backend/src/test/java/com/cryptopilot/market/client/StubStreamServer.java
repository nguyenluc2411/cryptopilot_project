package com.cryptopilot.market.client;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A local stand-in for the exchange's stream host: a minimal WebSocket server written against RFC 6455, so the
 * shard is tested through the real JDK {@code WebSocket} and real frames — handshake, text, ping and pong,
 * close — without a new dependency (the REST side's {@link StubExchange} does the same for HTTP).
 *
 * <p>Enough of the protocol for these tests and no more: unfragmented frames, no extensions. The test drives
 * each accepted {@link Connection}: sends text, pings, closes it politely or drops it, and reads the pongs and
 * the close the client sent.
 *
 * <p>Reference: Fette, I. &amp; Melnikov, A. (2011). <i>RFC 6455: The WebSocket Protocol</i>. IETF, §4.2 (the
 * opening handshake) and §5 (framing; a client masks its frames, a server does not).
 */
public final class StubStreamServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicInteger refusals = new AtomicInteger();
    private final Thread acceptor;

    public StubStreamServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofVirtual().start(this::accept);
    }

    /** The host to configure, {@code ws://127.0.0.1:<port>}, with a route appended when given. */
    public URI baseUrl(String route) {
        return URI.create("ws://127.0.0.1:" + server.getLocalPort() + route);
    }

    /** Answers the next {@code count} handshakes with 503 instead of upgrading. */
    public void refuseNext(int count) {
        refusals.set(count);
    }

    /** Every handshake's request target, in order, refused ones included. */
    public List<String> paths() {
        return List.copyOf(paths);
    }

    /** The connections upgraded so far, oldest first. */
    public List<Connection> connections() {
        return List.copyOf(connections);
    }

    /** Waits until this many connections have been upgraded, and answers the last one. */
    public Connection awaitConnection(int count) {
        await(() -> connections.size() >= count, "connection " + count);
        return connections.get(count - 1);
    }

    /** Waits until the condition holds, failing the test after five seconds. */
    public static void await(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.onSpinWait();
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
        for (Connection connection : connections) {
            connection.drop();
        }
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> handshake(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void handshake(Socket socket) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            List<String> lines = new ArrayList<>();
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                lines.add(line);
            }
            paths.add(lines.getFirst().split(" ")[1]);
            if (refusals.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                socket.close();
                return;
            }
            String key = lines.stream()
                    .filter(line -> line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18))
                    .map(line -> line.substring(18).trim())
                    .findFirst()
                    .orElseThrow();
            String accept = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Connection connection = new Connection(socket, in, out);
            connections.add(connection);
            connection.read();
        } catch (IOException | NoSuchAlgorithmException | RuntimeException ended) {
            closeQuietly(socket);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b == -1) {
                throw new IOException("the client hung up during the handshake");
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    /** One upgraded connection, seen from the server. */
    public static final class Connection {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final List<String> pongs = new CopyOnWriteArrayList<>();
        private volatile Integer closeCode;
        private volatile boolean ended;

        private Connection(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        /** Sends one text frame. */
        public void send(String text) {
            frame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        /** Sends a ping with this payload. */
        public void ping(String payload) {
            frame(0x9, payload.getBytes(StandardCharsets.UTF_8));
        }

        /** Sends a close frame with this status, as the exchange does at the 24-hour mark. */
        public void closeWith(int status) {
            frame(0x8, new byte[] {(byte) (status >> 8), (byte) status});
        }

        /** Cuts the TCP connection without a close frame, as a network failure does. */
        public void drop() {
            ended = true;
            closeQuietly(socket);
        }

        /** The payloads of the pongs the client sent. */
        public List<String> pongs() {
            return List.copyOf(pongs);
        }

        /** The status of the close frame the client sent, or null. */
        public Integer closeCode() {
            return closeCode;
        }

        /** Whether the connection has ended, from either side. */
        public boolean ended() {
            return ended;
        }

        private synchronized void frame(int opcode, byte[] payload) {
            try {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x80 | opcode);
                if (payload.length < 126) {
                    frame.write(payload.length);
                } else if (payload.length < 65536) {
                    frame.write(126);
                    frame.write(payload.length >> 8);
                    frame.write(payload.length);
                } else {
                    frame.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        frame.write((int) ((long) payload.length >> shift));
                    }
                }
                frame.write(payload);
                out.write(frame.toByteArray());
                out.flush();
            } catch (IOException gone) {
                ended = true;
            }
        }

        private void read() throws IOException {
            try {
                while (true) {
                    int first = in.read();
                    int second = in.read();
                    if (first == -1 || second == -1) {
                        return;
                    }
                    long length = second & 0x7F;
                    if (length == 126) {
                        length = (in.read() << 8) | in.read();
                    } else if (length == 127) {
                        length = 0;
                        for (int i = 0; i < 8; i++) {
                            length = (length << 8) | in.read();
                        }
                    }
                    byte[] mask = in.readNBytes(4);
                    byte[] payload = in.readNBytes((int) length);
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                    switch (first & 0x0F) {
                        case 0xA -> pongs.add(new String(payload, StandardCharsets.UTF_8));
                        case 0x8 -> {
                            closeCode = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                            frame(0x8, payload.length >= 2 ? new byte[] {payload[0], payload[1]} : new byte[0]);
                            return;
                        }
                        default -> {
                            // Text or anything else from the client: not used by these tests.
                        }
                    }
                }
            } finally {
                ended = true;
                closeQuietly(socket);
            }
        }
    }
}
