package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * One origin list governs both the REST API and the STOMP handshake: the configured origin passes the preflight and
 * opens the socket, any other origin is refused on both.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cryptopilot.web.cors.allowed-origins=" + CorsPolicyTest.WEB_APP)
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CorsPolicyTest {

    static final String WEB_APP = "https://app.cryptopilot.test";

    static final String FOREIGN = "https://evil.test";

    @Autowired
    private MockMvc mvc;

    @LocalServerPort
    private int port;

    @Test
    void TD53_aPreflightFromTheConfiguredOrigin_isAllowed() throws Exception {
        mvc.perform(options("/api/v1/plans")
                        .header(HttpHeaders.ORIGIN, WEB_APP)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_APP))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void TD53_aPreflightFromAnotherOrigin_isRefused() throws Exception {
        mvc.perform(options("/api/v1/plans")
                        .header(HttpHeaders.ORIGIN, FOREIGN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void TD53_aRequestFromAnotherOrigin_isRefused_andOneFromTheConfiguredOriginIsServed() throws Exception {
        mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, FOREIGN)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, WEB_APP))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_APP));
    }

    @Test
    void TD53_theStompHandshake_fromTheConfiguredOrigin_connects() throws Exception {
        StompSession session = connectFrom(WEB_APP);

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void TD53_theStompHandshake_fromAnotherOrigin_isRefused() {
        assertThatThrownBy(() -> connectFrom(FOREIGN)).isInstanceOf(ExecutionException.class);
    }

    private StompSession connectFrom(String origin) throws Exception {
        return connect(port, origin);
    }

    static StompSession connect(int port, String origin) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        try {
            return stomp.connectAsync("ws://localhost:" + port + "/ws", headers, new StompSessionHandlerAdapter() {})
                    .get(5, TimeUnit.SECONDS);
        } finally {
            stomp.stop();
        }
    }
}
