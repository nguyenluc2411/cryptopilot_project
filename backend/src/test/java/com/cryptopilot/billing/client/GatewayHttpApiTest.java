package com.cryptopilot.billing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SePay order API and the MoMo API over HTTP against a local stand-in server: each HTTP answer mapped to what the
 * gateway code reads, and a server that cannot answer turned into {@link GatewayUnavailableException} (NSF-13).
 */
class GatewayHttpApiTest {

    /** Status and body each path answers with. */
    private final Map<String, Object[]> answers = new ConcurrentHashMap<>();

    private final Map<String, String> authorization = new ConcurrentHashMap<>();

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                authorization.put(path, auth);
            }
            Object[] answer = answers.getOrDefault(path, new Object[] {404, ""});
            byte[] body = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders((int) answer[0], body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void NSF13_theSePayOrderQuery_readsTheAnswer_aMissingOrder_andAServerError() {
        SePayHttpOrderApi api = sepay();
        answers.put("/v1/order/detail/CP1", new Object[] {200, "{\"data\":{\"order_status\":\"CAPTURED\"}}"});
        answers.put("/v1/order/detail/CP-DOWN", new Object[] {500, "{}"});

        assertThat(api.orderDetail("CP1"))
                .hasValueSatisfying(json -> assertThat(json).contains("CAPTURED"));
        assertThat(authorization.get("/v1/order/detail/CP1")).startsWith("Basic ");
        assertThat(api.orderDetail("CP-NONE")).isEmpty();
        assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> api.orderDetail("CP-DOWN"));
    }

    @Test
    void NSF13_aSePayCancellation_mapsEveryAnswer() {
        SePayHttpOrderApi api = sepay();

        answers.put("/v1/order/cancel", new Object[] {200, "{}"});
        assertThat(api.cancel("CP1")).isEqualTo(SePayOrderApi.CancelAnswer.CANCELLED);
        answers.put("/v1/order/cancel", new Object[] {404, "{}"});
        assertThat(api.cancel("CP1")).isEqualTo(SePayOrderApi.CancelAnswer.UNKNOWN);
        answers.put("/v1/order/cancel", new Object[] {409, "{}"});
        assertThat(api.cancel("CP1")).isEqualTo(SePayOrderApi.CancelAnswer.REFUSED);
        for (int status : new int[] {401, 403, 429, 500}) {
            answers.put("/v1/order/cancel", new Object[] {status, "{}"});
            assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> api.cancel("CP1"));
        }
    }

    @Test
    void D147_anUnconfiguredSePay_isAskedNothing() {
        SePayHttpOrderApi api = new SePayHttpOrderApi(new SePayProperties("", "", "", "", base));

        assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> api.orderDetail("CP1"));
        assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> api.cancel("CP1"));
        assertThat(authorization).isEmpty();
    }

    @Test
    void NSF13_theMoMoApi_returnsTheBody_andRefusesAnEmptyOneOrAServerError() {
        MoMoHttpApi api = new MoMoHttpApi(new MoMoProperties("MOMO", "ACCESS", "secret", base, "https://ipn.test"));
        answers.put("/v2/gateway/api/query", new Object[] {200, "{\"resultCode\":0}"});
        answers.put("/v2/gateway/api/empty", new Object[] {200, ""});
        answers.put("/v2/gateway/api/down", new Object[] {500, "{}"});

        assertThat(api.post("/v2/gateway/api/query", Map.of("orderId", "CP1"))).contains("resultCode");
        assertThatExceptionOfType(GatewayUnavailableException.class)
                .isThrownBy(() -> api.post("/v2/gateway/api/empty", Map.of()));
        assertThatExceptionOfType(GatewayUnavailableException.class)
                .isThrownBy(() -> api.post("/v2/gateway/api/down", Map.of()));
    }

    private SePayHttpOrderApi sepay() {
        return new SePayHttpOrderApi(new SePayProperties("MERCHANT", "spsk_test_x", "ipn", base + "/checkout", base));
    }
}
