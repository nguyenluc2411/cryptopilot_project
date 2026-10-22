package com.cryptopilot.community.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.community.model.enums.PostMediaType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Cloudinary adapter against a local stand-in of its REST API (D-36): the signed upload form, reading a stored file
 * back with the Admin API, and the signed destroy call.
 *
 * <p>Rule: BR-44; D-98.
 */
class CloudinaryClientTest {

    private static final String CLOUD = "demo-cloud";
    private static final String KEY = "123456789012345";
    private static final String SECRET = "the-secret";
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private static final String PUBLIC_ID = "cryptopilot/posts/019b76da-a800-7000-8000-000000000001/file-1";

    private HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private CloudinaryClient client;

    private record Answer(int status, String body) {}

    private record Recorded(String method, String path, String query, String authorization, String body) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::answer);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new CloudinaryClient(
                new CloudinaryProperties(
                        CLOUD, KEY, SECRET, base, "cryptopilot/posts", Duration.ofSeconds(2), Duration.ofSeconds(2)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void D98_aForm_isSignedForItsKeyFormatsTimeAndNoOverwrite() {
        UploadTicket ticket = client.sign(PostMediaType.IMAGE, PUBLIC_ID, List.of("jpg", "png", "webp"));

        assertThat(ticket.uploadUrl()).endsWith("/v1_1/" + CLOUD + "/image/upload");
        assertThat(ticket.apiKey()).isEqualTo(KEY);
        assertThat(ticket.timestamp()).isEqualTo(NOW.getEpochSecond());
        assertThat(ticket.publicId()).isEqualTo(PUBLIC_ID);
        assertThat(ticket.signature())
                .isEqualTo(CloudinarySignature.sign(
                        Map.of(
                                "public_id",
                                PUBLIC_ID,
                                "timestamp",
                                Long.toString(NOW.getEpochSecond()),
                                "allowed_formats",
                                "jpg,png,webp",
                                "overwrite",
                                "false"),
                        SECRET));
        assertThat(ticket.overwrite()).isFalse();
        assertThat(client.sign(PostMediaType.VIDEO, PUBLIC_ID, List.of("mp4")).uploadUrl())
                .endsWith("/v1_1/" + CLOUD + "/video/upload");
    }

    @Test
    void BR44_aStoredImage_isDescribedFromTheAdminApi_withBasicAuthentication() {
        answers.put("/v1_1/" + CLOUD + "/resources/image/upload/" + PUBLIC_ID, new Answer(200, """
                {"public_id": "%s", "format": "PNG", "bytes": 345678, "width": 800, "height": 600,
                 "resource_type": "image", "secure_url": "https://res.cloudinary.com/demo-cloud/image/upload/v1/%s.png"}
                """.formatted(
                        PUBLIC_ID, PUBLIC_ID)));

        StoredMedia stored = client.describe(PostMediaType.IMAGE, PUBLIC_ID).orElseThrow();

        assertThat(stored.format()).isEqualTo("png");
        assertThat(stored.bytes()).isEqualTo(345_678);
        assertThat(stored.width()).isEqualTo(800);
        assertThat(stored.height()).isEqualTo(600);
        assertThat(stored.durationSeconds()).isNull();
        assertThat(stored.thumbnailUrl()).isNull();
        assertThat(stored.url()).endsWith(PUBLIC_ID + ".png");
        Recorded request = requests.getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.authorization())
                .isEqualTo("Basic "
                        + Base64.getEncoder().encodeToString((KEY + ":" + SECRET).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void D98_aFolder_isListedOldestFirst_pageByPage_withBasicAuthentication() {
        String listing = "/v1_1/" + CLOUD + "/resources/image/upload";
        answers.put(listing + "?prefix=cryptopilot/posts/&max_results=500&direction=asc", new Answer(200, """
                {"resources": [
                   {"public_id": "cryptopilot/posts/a/1", "created_at": "2026-10-18T01:00:00Z"},
                   {"public_id": "cryptopilot/posts/a/2", "created_at": "2026-10-19T01:00:00Z"}],
                 "next_cursor": "page-2"}
                """));
        answers.put(
                listing + "?prefix=cryptopilot/posts/&max_results=500&direction=asc&next_cursor=page-2",
                new Answer(200, """
                {"resources": [{"public_id": "cryptopilot/posts/b/3", "created_at": "2026-10-20T01:00:00Z"}]}
                """));

        StoredFilePage first = client.list(PostMediaType.IMAGE, "cryptopilot/posts", null);
        StoredFilePage second = client.list(PostMediaType.IMAGE, "cryptopilot/posts", first.nextCursor());

        assertThat(first.files())
                .extracting(StoredFilePage.File::publicId)
                .containsExactly("cryptopilot/posts/a/1", "cryptopilot/posts/a/2");
        assertThat(first.files().getFirst().createdAt()).isEqualTo(Instant.parse("2026-10-18T01:00:00Z"));
        assertThat(first.nextCursor()).isEqualTo("page-2");
        assertThat(second.files()).extracting(StoredFilePage.File::publicId).containsExactly("cryptopilot/posts/b/3");
        assertThat(second.nextCursor()).isNull();
        assertThat(requests.getFirst().authorization()).startsWith("Basic ");
    }

    @Test
    void D98_aListingTheStorageRefuses_isAStorageFailure() {
        answers.put(
                "/v1_1/" + CLOUD + "/resources/video/upload",
                new Answer(420, "{\"error\": {\"message\": \"Rate Limited\"}}"));

        assertThatThrownBy(() -> client.list(PostMediaType.VIDEO, "cryptopilot/posts", null))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void BR44_aStoredVideo_hasItsLengthRoundedUp_andAStill() {
        answers.put(
                "/v1_1/" + CLOUD + "/resources/video/upload/" + PUBLIC_ID, new Answer(200, """
                {"format": "mp4", "bytes": 10485760, "width": 1280, "height": 720, "duration": 299.2,
                 "secure_url": "https://res.cloudinary.com/demo-cloud/video/upload/v1/%s.mp4"}
                """.formatted(PUBLIC_ID)));

        StoredMedia stored = client.describe(PostMediaType.VIDEO, PUBLIC_ID).orElseThrow();

        assertThat(stored.durationSeconds()).isEqualTo(300);
        assertThat(stored.thumbnailUrl()).endsWith(PUBLIC_ID + ".jpg");
    }

    @Test
    void D98_nothingStoredUnderTheKey_isEmpty() {
        assertThat(client.describe(PostMediaType.IMAGE, PUBLIC_ID)).isEqualTo(Optional.empty());
    }

    @Test
    void D98_anErrorOfTheStorage_isAStorageFailure_thatNamesNoSecret() {
        answers.put("/v1_1/" + CLOUD + "/resources/image/upload/" + PUBLIC_ID, new Answer(500, "{}"));

        assertThatThrownBy(() -> client.describe(PostMediaType.IMAGE, PUBLIC_ID))
                .isInstanceOf(MediaStorageException.class)
                .message()
                .doesNotContain(SECRET);
    }

    @Test
    void D98_destroy_postsASignedForm_andAFileAlreadyGoneIsFine() {
        String destroy = "/v1_1/" + CLOUD + "/video/destroy";
        answers.put(destroy, new Answer(200, "{\"result\": \"ok\"}"));
        client.destroy(PostMediaType.VIDEO, PUBLIC_ID);

        Map<String, String> form = form(requests.getFirst().body());
        assertThat(requests.getFirst().method()).isEqualTo("POST");
        assertThat(requests.getFirst().path()).isEqualTo(destroy);
        assertThat(form)
                .containsEntry("public_id", PUBLIC_ID)
                .containsEntry("api_key", KEY)
                .containsEntry("timestamp", Long.toString(NOW.getEpochSecond()))
                .containsEntry(
                        "signature",
                        CloudinarySignature.sign(
                                Map.of("public_id", PUBLIC_ID, "timestamp", Long.toString(NOW.getEpochSecond())),
                                SECRET))
                .doesNotContainValue(SECRET);

        answers.put(destroy, new Answer(200, "{\"result\": \"not found\"}"));
        client.destroy(PostMediaType.VIDEO, PUBLIC_ID);

        answers.put(destroy, new Answer(200, "{\"result\": \"error\"}"));
        assertThatThrownBy(() -> client.destroy(PostMediaType.VIDEO, PUBLIC_ID))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void D98_aKeyThisModuleDoesNotIssue_isRefusedBeforeAnyRequest() {
        assertThatThrownBy(() -> client.describe(PostMediaType.IMAGE, "../../admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.destroy(PostMediaType.IMAGE, "a?b=c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void D98_theProperties_neverPrintTheSecret() {
        CloudinaryProperties properties = new CloudinaryProperties(
                CLOUD, KEY, SECRET, URI.create("https://api.cloudinary.com"), "f", Duration.ZERO, Duration.ZERO);

        assertThat(properties.toString()).doesNotContain(SECRET).doesNotContain(KEY);
        assertThat(properties.configured()).isTrue();
        assertThat(new CloudinaryProperties(
                                "", "", "", URI.create("https://api.cloudinary.com"), "f", Duration.ZERO, Duration.ZERO)
                        .configured())
                .isFalse();
    }

    private void answer(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        Answer answer = answers.getOrDefault(path + "?" + query, answers.getOrDefault(path, new Answer(404, "{}")));
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> form(String body) {
        return java.util.Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
