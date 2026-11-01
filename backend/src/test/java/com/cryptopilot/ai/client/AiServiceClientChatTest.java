package com.cryptopilot.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.ai.model.AiChatAnswer;
import com.cryptopilot.ai.model.AiChatRequest;
import com.cryptopilot.ai.model.ChatTurn;
import com.cryptopilot.ai.model.enums.SenderRole;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AiServiceClientChatTest {

    private static final String SECRET = "s".repeat(48);

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> bodySeen = new AtomicReference<>();
    private final AtomicReference<String> secretSeen = new AtomicReference<>();
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat", exchange -> {
            bodySeen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            secretSeen.set(exchange.getRequestHeaders().getFirst(AiServiceClient.SECRET_HEADER));
            if (answer.get() == null) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                answer.set("{}");
            }
            byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void NSF16_theRequestCarriesTheConfigurationTheHistoryTheQuestionAndTheContext_withTheSecret() {
        answer.set("""
                {"text":"RSI 72 là vùng quá mua.","model":"gemini-test","inputTokens":163,"outputTokens":59}""");
        JsonNode context = json.readTree("{\"market\":{\"symbol\":\"BTCUSDT\",\"setupScore\":72}}");

        AiChatAnswer result;
        try (AiServiceClient client = client()) {
            result = client.chat(request(context, Duration.ofSeconds(5)));
        }

        assertThat(result).isEqualTo(new AiChatAnswer("RSI 72 là vùng quá mua.", "gemini-test", 163, 59));
        assertThat(secretSeen.get()).isEqualTo(SECRET);
        JsonNode sent = json.readTree(bodySeen.get());
        assertThat(sent.path("model").asString()).isEqualTo("gemini-test");
        assertThat(sent.path("systemPrompt").asString()).isEqualTo("Explain, never advise.");
        assertThat(sent.path("temperature").asDouble()).isEqualTo(0.3);
        assertThat(sent.path("maxOutputTokens").asInt()).isEqualTo(1024);
        assertThat(sent.path("history").get(0).path("role").asString()).isEqualTo("user");
        assertThat(sent.path("history").get(1).path("role").asString()).isEqualTo("assistant");
        assertThat(sent.path("history").get(1).path("text").asString()).isEqualTo("A momentum gauge.");
        assertThat(sent.path("question").asString()).isEqualTo("And 72?");
        assertThat(sent.path("context").path("market").path("setupScore").asInt())
                .isEqualTo(72);
    }

    @Test
    void NSF16_aGeneralQuestion_sendsNoContext_andMissingCountsDefault() {
        answer.set("{\"text\":\"ok\"}");

        AiChatAnswer result = client().chat(request(null, Duration.ofSeconds(5)));

        assertThat(result).isEqualTo(new AiChatAnswer("ok", "gemini-test", 0, 0));
        assertThat(json.readTree(bodySeen.get()).has("context")).isFalse();
    }

    @Test
    void NSF16_anErrorAnswer_orAnEmptyText_isAnAiServiceException() {
        status.set(504);
        answer.set("{\"code\":\"LLM_TIMEOUT\",\"retryable\":true,\"message\":\"too slow\"}");
        assertThatThrownBy(() -> client().chat(request(null, Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(AiServiceException.class, e -> {
                    assertThat(e.code()).isEqualTo("LLM_TIMEOUT");
                    assertThat(e.retryable()).isTrue();
                });

        status.set(200);
        answer.set("{\"text\":\"  \"}");
        assertThatThrownBy(() -> client().chat(request(null, Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(
                        AiServiceException.class, e -> assertThat(e.code()).isEqualTo("BAD_ANSWER"));
    }

    @Test
    void NSF16_theCallIsBoundedByTheQuestionsTimeout_notTheNewsOne() {
        // The server never answers: the 60-second limit of SRS 3.9.1 is the request's, here 300 ms.
        assertThatThrownBy(() -> client().chat(request(null, Duration.ofMillis(300))))
                .isInstanceOfSatisfying(AiServiceException.class, e -> {
                    assertThat(e.code()).isEqualTo(AiServiceException.UNREACHABLE);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void NSF16_withoutTheSharedSecret_nothingIsSent() {
        AiServiceClient unconfigured = new AiServiceClient(
                new AiServiceProperties(uri(), "", Duration.ofSeconds(1), Duration.ofSeconds(130)), json);

        assertThatThrownBy(() -> unconfigured.chat(request(null, Duration.ofSeconds(1))))
                .isInstanceOfSatisfying(AiServiceException.class, e -> assertThat(e.code())
                        .isEqualTo(AiServiceException.NOT_CONFIGURED));
        assertThat(bodySeen.get()).isNull();
    }

    private static AiChatRequest request(JsonNode context, Duration timeout) {
        return new AiChatRequest(
                "gemini-test",
                "Explain, never advise.",
                new BigDecimal("0.30"),
                1024,
                List.of(
                        new ChatTurn(SenderRole.USER, "What is RSI?"),
                        new ChatTurn(SenderRole.ASSISTANT, "A momentum gauge.")),
                "And 72?",
                context,
                timeout);
    }

    private AiServiceClient client() {
        return new AiServiceClient(
                new AiServiceProperties(uri(), SECRET, Duration.ofSeconds(1), Duration.ofSeconds(130)), json);
    }

    private URI uri() {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort());
    }
}
