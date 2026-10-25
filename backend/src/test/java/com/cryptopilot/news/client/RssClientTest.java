package com.cryptopilot.news.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

/**
 * The feed reader against a server of the test's own, on the loopback address: RSS and Atom read item by item, the
 * agent sent, redirects followed and bounded, and every way a feed can fail reported with its reason. The
 * private-address guard is what keeps the same reader off the server's own network in production.
 *
 * <p>Rule: NSF-10 (the read is bounded in time); UC-49 (the test read of a feed).
 */
class RssClientTest {

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Example Markets</title>
              <item>
                <title>Bitcoin steadies near $81,700</title>
                <link>https://example.com/markets/btc-steadies?utm_source=rss</link>
                <pubDate>Fri, 24 Oct 2026 13:08:06 GMT</pubDate>
                <description><![CDATA[<p>Bitcoin traded <b>flat</b>.</p>]]></description>
                <category domain="https://example.com/markets">Markets</category>
                <category>Bitcoin</category>
              </item>
              <item><title>No link, no date</title></item>
            </channel></rss>""";

    private static final String ATOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom Outlet</title>
              <id>urn:uuid:feed</id><updated>2026-10-24T10:00:00Z</updated>
              <entry>
                <title>Ether ETF outflows continue</title>
                <id>https://atom.example.com/eth-outflows</id>
                <updated>2026-10-24T09:30:00Z</updated>
                <content type="html">&lt;p&gt;Outflows passed $1B.&lt;/p&gt;</content>
              </entry>
              <entry><title>Tag id</title><id>tag:atom.example.com,2026:2</id>
                <updated>2026-10-24T09:00:00Z</updated></entry>
            </feed>""";

    private HttpServer server;
    private ExecutorService executor;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<String> agentSeen = new AtomicReference<>();
    private final AtomicReference<String> acceptSeen = new AtomicReference<>();
    private RssClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/rss", exchange -> {
            agentSeen.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            send(exchange, 200, "application/rss+xml; charset=UTF-8", RSS);
        });
        server.createContext("/atom", exchange -> send(exchange, 200, "application/atom+xml", ATOM));
        server.createContext("/article", exchange -> {
            agentSeen.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            acceptSeen.set(exchange.getRequestHeaders().getFirst("Accept"));
            send(exchange, 200, "text/html; charset=UTF-8", "<html><body><p>Bitcoin holds.</p></body></html>");
        });
        server.createContext("/moved-article", exchange -> redirect(exchange, "/article"));
        server.createContext("/moved", exchange -> redirect(exchange, "/rss"));
        server.createContext("/loop", exchange -> redirect(exchange, "/loop"));
        server.createContext("/no-location", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> send(exchange, 404, "text/plain", "not here"));
        server.createContext("/html", exchange -> send(exchange, 200, "text/html", "<html><body>Hi</body></html>"));
        server.createContext("/xxe", exchange -> send(exchange, 200, "application/rss+xml", """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
                <rss version="2.0"><channel><title>&secret;</title></channel></rss>"""));
        server.createContext("/big", exchange -> send(exchange, 200, "application/rss+xml", "x".repeat(4096)));
        server.createContext("/gzip", exchange -> sendGzip(exchange, RSS));
        server.createContext("/bomb", exchange -> sendGzip(exchange, " ".repeat(64 * 1024)));
        server.createContext("/bad-gzip", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            send(exchange, 200, "application/rss+xml", "not gzip at all");
        });
        server.createContext("/slow", exchange -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, "application/rss+xml", RSS);
        });
        server.start();
        client = new RssClient(properties(true, DataSize.ofKilobytes(2)));
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        client.close();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void NSF10_anRssFeed_isReadItemByItemAsTheOutletWroteIt() {
        RssFeed feed = client.read(url("/rss"), "Outlet/1.0");

        assertThat(feed.title()).isEqualTo("Example Markets");
        assertThat(feed.items()).hasSize(2);
        RssItem first = feed.items().getFirst();
        assertThat(first.title()).isEqualTo("Bitcoin steadies near $81,700");
        assertThat(first.link()).isEqualTo("https://example.com/markets/btc-steadies?utm_source=rss");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-24T13:08:06Z"));
        assertThat(first.description()).isEqualTo("<p>Bitcoin traded <b>flat</b>.</p>");
        assertThat(first.categories()).containsExactly("Markets", "https://example.com/markets", "Bitcoin");
        RssItem second = feed.items().get(1);
        assertThat(second.link()).isNull();
        assertThat(second.publishedAt()).isNull();
        assertThat(second.description()).isNull();
        assertThat(second.categories()).isEmpty();
    }

    @Test
    void NSF10_anAtomFeed_givesItsContent_itsUpdatedTime_andAnIdThatIsAnAddress() {
        RssFeed feed = client.read(url("/atom"), null);

        assertThat(feed.title()).isEqualTo("Atom Outlet");
        RssItem first = feed.items().getFirst();
        assertThat(first.link()).isEqualTo("https://atom.example.com/eth-outflows");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-24T09:30:00Z"));
        assertThat(first.description()).isEqualTo("<p>Outflows passed $1B.</p>");
        assertThat(feed.items().get(1).link()).as("a tag URI is not an address").isNull();
    }

    @Test
    void UC49_theSourcesAgentIsSent_andTheDefaultWhenItHasNone() {
        client.read(url("/rss"), "Mozilla/5.0 Outlet");
        assertThat(agentSeen.get()).isEqualTo("Mozilla/5.0 Outlet");

        client.read(url("/rss"), null);
        assertThat(agentSeen.get()).isEqualTo("TestAgent/1.0");
        client.read(url("/rss"), "  ");
        assertThat(agentSeen.get()).isEqualTo("TestAgent/1.0");
    }

    @Test
    void NSF11_anArticlePage_isReadAsSent_afterItsRedirects_withTheSourcesAgent() {
        WebPage page = client.readPage(url("/moved-article"), "Outlet/2.0");

        assertThat(page.url()).isEqualTo(url("/article"));
        assertThat(new String(page.body(), StandardCharsets.UTF_8)).contains("Bitcoin holds.");
        assertThat(page.contentType()).isEqualTo("text/html; charset=UTF-8");
        assertThat(agentSeen.get()).isEqualTo("Outlet/2.0");
        assertThat(acceptSeen.get()).startsWith("text/html");
    }

    @Test
    void NSF11_anArticlePage_isGuardedLikeAFeed() {
        assertThatThrownBy(() -> client.readPage(url("/missing"), null))
                .isInstanceOf(RssClientException.class)
                .extracting(e -> ((RssClientException) e).reason())
                .isEqualTo(RssClientException.Reason.HTTP_STATUS);
        assertThatThrownBy(() -> client.readPage(url("/big"), null))
                .extracting(e -> ((RssClientException) e).reason())
                .isEqualTo(RssClientException.Reason.TOO_LARGE);
        try (RssClient guarded = new RssClient(properties(false, DataSize.ofKilobytes(2)))) {
            assertThatThrownBy(() -> guarded.readPage(url("/article"), null))
                    .extracting(e -> ((RssClientException) e).reason())
                    .isEqualTo(RssClientException.Reason.BLOCKED_ADDRESS);
        }
    }

    @Test
    void NSF10_aRedirect_isFollowed_butNotForever() {
        assertThat(client.read(url("/moved"), null).items()).hasSize(2);

        assertFails(url("/loop"), RssClientException.Reason.HTTP_STATUS);
        assertFails(url("/no-location"), RssClientException.Reason.HTTP_STATUS);
    }

    @Test
    void UC49_anErrorStatus_orAPageThatIsNoFeed_isReported() {
        assertFails(url("/missing"), RssClientException.Reason.HTTP_STATUS);
        assertFails(url("/html"), RssClientException.Reason.NOT_A_FEED);
    }

    @Test
    void UC49_aDocumentTypeDeclaration_isRefused_soNoEntityIsResolved() {
        assertFails(url("/xxe"), RssClientException.Reason.NOT_A_FEED);
    }

    @Test
    void NSF10_aResponsePastTheLimit_isCutOff_compressedOrNot() {
        assertFails(url("/big"), RssClientException.Reason.TOO_LARGE);
        assertFails(url("/bomb"), RssClientException.Reason.TOO_LARGE);
    }

    @Test
    void NSF10_aGzippedFeed_isDecoded_andBrokenGzipIsNoFeed() {
        assertThat(client.read(url("/gzip"), null).items()).hasSize(2);

        assertFails(url("/bad-gzip"), RssClientException.Reason.NOT_A_FEED);
    }

    @Test
    void NSF10_aServerThatDoesNotAnswerInTime_isUnreachable() {
        assertThatThrownBy(() -> client.read(url("/slow"), null))
                .isInstanceOf(RssClientException.class)
                .hasMessageContaining("did not answer")
                .extracting(e -> ((RssClientException) e).reason())
                .isEqualTo(RssClientException.Reason.UNREACHABLE);
    }

    @Test
    void NSF10_aPortNobodyListensOn_isUnreachable() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }

        assertFails("http://127.0.0.1:" + closed + "/rss", RssClientException.Reason.UNREACHABLE);
    }

    @Test
    void UC49_theServersOwnNetwork_isNotRead() {
        try (RssClient guarded = new RssClient(properties(false, DataSize.ofKilobytes(2)))) {
            assertThatThrownBy(() -> guarded.read(url("/rss"), null))
                    .isInstanceOf(RssClientException.class)
                    .extracting(e -> ((RssClientException) e).reason())
                    .isEqualTo(RssClientException.Reason.BLOCKED_ADDRESS);
        }
        assertThat(agentSeen.get()).as("no request reached the server").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/feed", "https:///feed", "not a url", "/rss"})
    void UC49_anythingButAWebAddress_isInvalid(String address) {
        assertFails(address, RssClientException.Reason.INVALID_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "10.1.2.3",
                "172.16.0.1",
                "192.168.1.1",
                "169.254.169.254",
                "0.0.0.0",
                "100.64.0.1",
                "100.127.255.254",
                "224.0.0.1",
                "::1",
                "fc00::1",
                "fd12:3456::1",
                "fe80::1"
            })
    void UC49_loopbackPrivateLinkLocalCgnatAndMulticast_areBlocked(String literal) throws IOException {
        assertThat(RssClient.isBlocked(InetAddress.getByName(literal))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "100.128.0.1", "100.63.255.255", "2001:4860:4860::8888"})
    void UC49_publicAddresses_areNotBlocked(String literal) throws IOException {
        assertThat(RssClient.isBlocked(InetAddress.getByName(literal))).isFalse();
    }

    private void assertFails(String address, RssClientException.Reason reason) {
        assertThatThrownBy(() -> client.read(address, null))
                .isInstanceOf(RssClientException.class)
                .extracting(e -> ((RssClientException) e).reason())
                .isEqualTo(reason);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static RssClientProperties properties(boolean allowPrivate, DataSize maxFeedSize) {
        return new RssClientProperties(
                Duration.ofSeconds(2), Duration.ofMillis(500), "TestAgent/1.0", maxFeedSize, 3, allowPrivate);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendGzip(HttpExchange exchange, String body) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(body.getBytes(StandardCharsets.UTF_8));
        }
        exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
        byte[] bytes = compressed.toByteArray();
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
