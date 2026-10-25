package com.cryptopilot.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.ai.NewsSummaryRequest;
import com.cryptopilot.ai.NewsSummaryResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend's side of the AI Service contract against a stand-in on the loopback address: the request as the AI
 * Service expects it, with the shared secret, and every way it can fail turned into a code and whether a retry may
 * succeed.
 *
 * <p>Rule: NSF-11; ADR-004.
 */
class AiServiceClientTest {

    private static final String SECRET = "s".repeat(48);
    private static final NewsSummaryRequest REQUEST = new NewsSummaryRequest(
            "gemini-test-lite", "Summarize.", 0.2, 256, "Bitcoin holds", "CoinDesk", "Bitcoin traded flat.", null);

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> bodySeen = new AtomicReference<>();
    private final AtomicReference<String> secretSeen = new AtomicReference<>();
    private final AtomicReference<Answer> answer = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;

    private record Answer(int status, String body) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/news/summary", exchange -> {
            bodySeen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            secretSeen.set(exchange.getRequestHeaders().getFirst(AiServiceClient.SECRET_HEADER));
            Answer next = answer.get();
            if (next == null) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                next = new Answer(200, "{}");
            }
            send(exchange, next);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void NSF11_theRequestCarriesTheModelTheInstructionsAndTheArticle_withTheSecret() {
        answer.set(new Answer(200, """
                {"title":"Bitcoin giữ giá","summary":"Bitcoin đi ngang.","sentiment":"NEUTRAL","model":"gemini-test-lite",
                 "inputTokens":210,"outputTokens":40}"""));

        NewsSummaryResult result;
        try (AiServiceClient client = client(SECRET)) {
            result = client.summarizeNews(REQUEST.withFeedback("too long"));
        }

        assertThat(result)
                .isEqualTo(new NewsSummaryResult(
                        "Bitcoin giữ giá", "Bitcoin đi ngang.", "NEUTRAL", "gemini-test-lite", 210, 40));
        assertThat(secretSeen.get()).isEqualTo(SECRET);
        JsonNode sent = json.readTree(bodySeen.get());
        assertThat(sent.path("model").asString()).isEqualTo("gemini-test-lite");
        assertThat(sent.path("systemPrompt").asString()).isEqualTo("Summarize.");
        assertThat(sent.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(sent.path("maxOutputTokens").asInt()).isEqualTo(256);
        assertThat(sent.path("article").path("title").asString()).isEqualTo("Bitcoin holds");
        assertThat(sent.path("article").path("source").asString()).isEqualTo("CoinDesk");
        assertThat(sent.path("article").path("text").asString()).isEqualTo("Bitcoin traded flat.");
        assertThat(sent.path("feedback").asString()).isEqualTo("too long");
    }

    @Test
    void NSF11_optionalFieldsAreLeftOut_andMissingCountsDefault() {
        answer.set(new Answer(200, "{\"title\":\"t\",\"summary\":\"s\",\"sentiment\":\"BULLISH\"}"));
        NewsSummaryRequest bare = new NewsSummaryRequest("m", "p", 0, 64, "t", null, "x", null);

        NewsSummaryResult result = client(SECRET).summarizeNews(bare);

        assertThat(result).isEqualTo(new NewsSummaryResult("t", "s", "BULLISH", "m", 0, 0));
        JsonNode sent = json.readTree(bodySeen.get());
        assertThat(sent.has("feedback")).isFalse();
        assertThat(sent.path("article").has("source")).isFalse();
    }

    @Test
    void NSF11_anErrorAnswer_passesOnTheCodeAndWhetherARetryMaySucceed() {
        answer.set(new Answer(429, "{\"code\":\"LLM_RATE_LIMITED\",\"retryable\":true,\"message\":\"slow down\"}"));
        assertFailure(AiServiceException.RATE_LIMITED, true, "slow down");

        answer.set(new Answer(502, "{\"code\":\"LLM_REJECTED\",\"retryable\":false,\"message\":\"model not found\"}"));
        assertFailure("LLM_REJECTED", false, "model not found");

        answer.set(new Answer(503, ""));
        assertFailure("HTTP_503", true, "the AI Service answered HTTP 503");

        answer.set(new Answer(401, "{\"code\":\"UNAUTHORIZED\",\"retryable\":false}"));
        assertFailure("UNAUTHORIZED", false, "the AI Service answered HTTP 401");
    }

    @Test
    void NSF11_anAnswerThatIsNotJson_orHasNoSummary_isABadAnswer() {
        answer.set(new Answer(200, "<html>"));
        assertFailure("BAD_ANSWER", true, "not JSON");

        answer.set(new Answer(200, "{\"title\":\"t\",\"summary\":\" \",\"sentiment\":\"NEUTRAL\"}"));
        assertFailure("BAD_ANSWER", false, "has no summary");

        answer.set(new Answer(200, "{\"title\":\"t\",\"summary\":\"s\"}"));
        assertFailure("BAD_ANSWER", false, "has no sentiment");

        answer.set(new Answer(200, "{\"summary\":\"s\",\"sentiment\":\"NEUTRAL\"}"));
        assertFailure("BAD_ANSWER", false, "has no title");
    }

    @Test
    void NSF11_aCallWhoseThreadIsInterrupted_isUnreachable_andKeepsTheInterrupt() {
        answer.set(new Answer(200, "{\"title\":\"t\",\"summary\":\"s\",\"sentiment\":\"NEUTRAL\"}"));
        AiServiceClient client = client(SECRET);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.summarizeNews(REQUEST))
                    .isInstanceOfSatisfying(AiServiceException.class, e -> {
                        assertThat(e.code()).isEqualTo(AiServiceException.UNREACHABLE);
                        assertThat(e.getMessage()).contains("interrupted");
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void NSF11_withoutTheSecret_nothingIsSent() {
        assertThatThrownBy(() -> client(" ").summarizeNews(REQUEST))
                .isInstanceOfSatisfying(AiServiceException.class, e -> {
                    assertThat(e.code()).isEqualTo(AiServiceException.NOT_CONFIGURED);
                    assertThat(e.stopsTheRun()).isTrue();
                });
        assertThat(bodySeen.get()).isNull();
        assertThat(new AiServiceProperties(url(), null, Duration.ofSeconds(1), Duration.ofSeconds(1)).configured())
                .isFalse();
    }

    @Test
    void NSF11_aServiceThatIsDownOrSlow_isUnreachable_andRetryable() throws IOException {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        AiServiceClient down = new AiServiceClient(
                new AiServiceProperties(
                        URI.create("http://127.0.0.1:" + closed), SECRET, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                json);
        assertThatThrownBy(() -> down.summarizeNews(REQUEST)).isInstanceOfSatisfying(AiServiceException.class, e -> {
            assertThat(e.code()).isEqualTo(AiServiceException.UNREACHABLE);
            assertThat(e.retryable()).isTrue();
            assertThat(e.stopsTheRun()).isTrue();
        });

        AiServiceClient impatient = new AiServiceClient(
                new AiServiceProperties(url(), SECRET, Duration.ofSeconds(1), Duration.ofMillis(300)), json);
        assertThatThrownBy(() -> impatient.summarizeNews(REQUEST))
                .isInstanceOfSatisfying(AiServiceException.class, e -> assertThat(e.getMessage())
                        .contains("did not answer in time"));
    }

    private void assertFailure(String code, boolean retryable, String message) {
        assertThatThrownBy(() -> client(SECRET).summarizeNews(REQUEST))
                .isInstanceOfSatisfying(AiServiceException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.retryable()).isEqualTo(retryable);
                    assertThat(e.getMessage()).contains(message);
                });
    }

    private AiServiceClient client(String secret) {
        return new AiServiceClient(
                new AiServiceProperties(url(), secret, Duration.ofSeconds(1), Duration.ofSeconds(5)), json);
    }

    private URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void send(HttpExchange exchange, Answer answer) throws IOException {
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
