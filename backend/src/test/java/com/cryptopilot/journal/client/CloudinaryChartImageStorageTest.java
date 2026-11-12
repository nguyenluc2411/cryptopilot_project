package com.cryptopilot.journal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The chart image storage on Cloudinary against a local stand-in: a form signed for a new key in the owner's folder,
 * the image read back from the Admin API, the signed delete, and the answers that are failures.
 *
 * <p>Rule: BR-40; SRS 3.6.1; D-98.
 */
class CloudinaryChartImageStorageTest {

    private static final String CLOUD = "demo-cloud";
    private static final String KEY = "the-key";
    private static final String SECRET = "the-secret";
    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final UUID OWNER = UUID.fromString("019b76da-a800-7000-8000-0000000000e1");
    private static final String PUBLIC_ID = "cryptopilot/journal/" + OWNER + "/019b76da-a800-7000-8000-0000000000e2";

    private HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private CloudinaryChartImageStorage storage;

    private record Answer(int status, String body) {}

    private record Recorded(String method, String path, String authorization, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::answer);
        server.start();
        storage = new CloudinaryChartImageStorage(
                properties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), CLOUD),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void D98_aForm_isSignedForANewKeyInTheOwnersFolder_imagesOnly_withoutOverwrite() {
        ChartImageUploadTicket ticket = storage.sign(OWNER);

        assertThat(ticket.uploadUrl()).endsWith("/v1_1/" + CLOUD + "/image/upload");
        assertThat(ticket.apiKey()).isEqualTo(KEY);
        assertThat(ticket.timestamp()).isEqualTo(NOW.getEpochSecond());
        assertThat(ticket.allowedFormats()).containsExactly("jpg", "jpeg", "png", "webp");
        assertThat(storage.issuedTo(OWNER, ticket.publicId())).isTrue();
        assertThat(ticket.signature())
                .isEqualTo(CloudinaryChartImageStorage.signature(
                        Map.of(
                                "public_id",
                                ticket.publicId(),
                                "timestamp",
                                Long.toString(NOW.getEpochSecond()),
                                "allowed_formats",
                                "jpg,jpeg,png,webp",
                                "overwrite",
                                "false"),
                        SECRET));
    }

    /** A key of another owner, of the post folder, or of any other shape, was not signed for this owner. */
    @Test
    void BR40_onlyAKeySignedForTheOwner_isTheirs() {
        assertThat(storage.issuedTo(OWNER, PUBLIC_ID)).isTrue();
        assertThat(storage.issuedTo(UUID.randomUUID(), PUBLIC_ID)).isFalse();
        assertThat(storage.issuedTo(OWNER, "cryptopilot/posts/" + OWNER + "/019b76da-a800-7000-8000-0000000000e2"))
                .isFalse();
        assertThat(storage.issuedTo(OWNER, "cryptopilot/journal/" + OWNER + "/../other"))
                .isFalse();
        assertThat(storage.issuedTo(OWNER, null)).isFalse();
    }

    @Test
    void BR40_aStoredImage_isReadBackFromTheAdminApi_withBasicAuthentication() {
        answers.put("/v1_1/" + CLOUD + "/resources/image/upload/" + PUBLIC_ID, new Answer(200, """
                {"public_id": "%s", "format": "PNG", "bytes": 345678,
                 "secure_url": "https://res.cloudinary.com/demo-cloud/image/upload/v1/%s.png"}
                """.formatted(
                        PUBLIC_ID, PUBLIC_ID)));

        StoredChartImage stored = storage.describe(PUBLIC_ID).orElseThrow();

        assertThat(stored.format()).isEqualTo("png");
        assertThat(stored.bytes()).isEqualTo(345_678);
        assertThat(stored.url()).endsWith(PUBLIC_ID + ".png");
        assertThat(requests.getFirst().method()).isEqualTo("GET");
        assertThat(requests.getFirst().authorization())
                .isEqualTo("Basic "
                        + Base64.getEncoder().encodeToString((KEY + ":" + SECRET).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void BR40_aKeyWithNothingStored_isEmpty() {
        assertThat(storage.describe(PUBLIC_ID)).isEmpty();
    }

    @Test
    void D98_anAnswerWithoutItsFields_orAnError_isAStorageFailure() {
        String path = "/v1_1/" + CLOUD + "/resources/image/upload/" + PUBLIC_ID;
        answers.put(path, new Answer(200, "{\"format\": \"png\"}"));
        assertThatThrownBy(() -> storage.describe(PUBLIC_ID)).isInstanceOf(ChartImageStorageException.class);

        answers.put(path, new Answer(500, "{}"));
        assertThatThrownBy(() -> storage.describe(PUBLIC_ID)).isInstanceOf(ChartImageStorageException.class);
    }

    @Test
    void D98_anImage_isDestroyedWithASignedForm_andOneAlreadyGoneIsNoError() {
        String destroy = "/v1_1/" + CLOUD + "/image/destroy";
        answers.put(destroy, new Answer(200, "{\"result\": \"ok\"}"));
        storage.destroy(PUBLIC_ID);

        String body = requests.getFirst().body();
        assertThat(requests.getFirst().method()).isEqualTo("POST");
        assertThat(body).contains("api_key=" + KEY).contains("timestamp=" + NOW.getEpochSecond());
        assertThat(body)
                .contains("signature="
                        + CloudinaryChartImageStorage.signature(
                                Map.of("public_id", PUBLIC_ID, "timestamp", Long.toString(NOW.getEpochSecond())),
                                SECRET));

        answers.put(destroy, new Answer(200, "{\"result\": \"not found\"}"));
        storage.destroy(PUBLIC_ID);
    }

    @Test
    void D98_aRefusedOrFailedDestroy_isAStorageFailure() {
        String destroy = "/v1_1/" + CLOUD + "/image/destroy";
        answers.put(destroy, new Answer(200, "{\"result\": \"error\"}"));
        assertThatThrownBy(() -> storage.destroy(PUBLIC_ID)).isInstanceOf(ChartImageStorageException.class);

        answers.put(destroy, new Answer(500, "{}"));
        assertThatThrownBy(() -> storage.destroy(PUBLIC_ID)).isInstanceOf(ChartImageStorageException.class);
    }

    @Test
    void D98_withoutAnAccount_thereIsNoCloudinaryStorage_andTheFallbackRefusesUploads() {
        assertThatThrownBy(() -> new CloudinaryChartImageStorage(
                        properties(URI.create("http://127.0.0.1"), ""), Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class);

        UnavailableChartImageStorage none = new UnavailableChartImageStorage();
        assertThatThrownBy(() -> none.sign(OWNER))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.MEDIA_STORAGE_UNAVAILABLE));
        assertThat(none.issuedTo(OWNER, PUBLIC_ID)).isFalse();
        assertThat(none.describe(PUBLIC_ID)).isEmpty();
        none.destroy(PUBLIC_ID);
        assertThat(properties(URI.create("http://127.0.0.1"), CLOUD).toString()).doesNotContain(SECRET);
    }

    private static ChartImageStorageProperties properties(URI base, String cloud) {
        return new ChartImageStorageProperties(
                cloud, KEY, SECRET, base, "cryptopilot/journal", Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private void answer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        requests.add(new Recorded(
                exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"), body));
        Answer answer = answers.getOrDefault(path, new Answer(404, "{}"));
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
