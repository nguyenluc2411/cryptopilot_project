package com.cryptopilot.common.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With no origin configured — production before {@code CORS_ALLOWED_ORIGINS} is set — every cross-origin caller is
 * refused, on REST and on the STOMP handshake, localhost included.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.3.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cryptopilot.web.cors.allowed-origins=")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CorsWithoutOriginsTest {

    @Autowired
    private MockMvc mvc;

    @LocalServerPort
    private int port;

    @ParameterizedTest
    @ValueSource(strings = {"https://app.cryptopilot.test", "http://localhost:5173"})
    void TD53_noConfiguredOrigin_refusesEveryPreflight(String origin) throws Exception {
        mvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://app.cryptopilot.test", "http://localhost:5173"})
    void TD53_noConfiguredOrigin_refusesEveryStompHandshake(String origin) {
        assertThatThrownBy(() -> CorsPolicyTest.connect(port, origin)).isInstanceOf(ExecutionException.class);
    }
}
