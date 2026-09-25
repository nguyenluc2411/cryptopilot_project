package com.cryptopilot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Requests Spring cannot bind answer MSG01 in the shape every other error has — {@code code}, {@code messageCode},
 * {@code errors} naming the parameter or field — and never with a Java type or a parser message in the body.
 *
 * <p>Rule: TECHNICAL_DESIGN 5.1 (MSG01); SRS 4.2.4 (no internal detail in an error).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BindingErrorsTest {

    private static final String CANDLES = "/api/v1/market/spot/BTCUSDT/candles";

    @Autowired
    private MockMvc mvc;

    /** A timestamp that is not ISO 8601. */
    @Test
    void TD51_aMalformedTimestamp_isMsg01NamingTheParameter() throws Exception {
        assertMsg01(mvc.perform(get(CANDLES).param("tf", "1h").param("from", "yesterday")), "from", "is invalid");
        assertMsg01(
                mvc.perform(get(CANDLES).param("tf", "1h").param("to", "2026-13-45T00:00:00Z")), "to", "is invalid");
    }

    /** A number that is not one. */
    @Test
    void TD51_aMalformedNumber_isMsg01NamingTheParameter() throws Exception {
        assertMsg01(mvc.perform(get(CANDLES).param("tf", "1h").param("limit", "ten")), "limit", "is invalid");
        assertMsg01(
                mvc.perform(get("/api/v1/market/pairs").param("market", "spot").param("page", "1.5")),
                "page",
                "is invalid");
    }

    /** A timeframe outside BR-08 is refused by the service, with the same code and message. */
    @Test
    void BR08_anUnknownTimeframe_isMsg01() throws Exception {
        mvc.perform(get(CANDLES).param("tf", "5m"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.messageCode").value("MSG01"));
    }

    /** A required parameter that is missing. */
    @Test
    void TD51_aMissingRequiredParameter_isMsg01NamingIt() throws Exception {
        assertMsg01(mvc.perform(get("/api/v1/market/pairs")), "market", "is required");
        assertMsg01(mvc.perform(get(CANDLES)), "tf", "is required");
    }

    /** A body that is not JSON. */
    @Test
    void TD51_aBodyThatIsNotJson_isMsg01OnTheBody() throws Exception {
        assertMsg01(
                mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"a@b.invalid\", \"password\": ")),
                "body",
                "is not readable");
    }

    /** A body field of the wrong type is named. */
    @Test
    void TD51_aBodyFieldOfTheWrongType_isMsg01NamingTheField() throws Exception {
        assertMsg01(
                mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"a@b.invalid\", \"password\": \"x\", \"rememberMe\": [1]}")),
                "rememberMe",
                "is not readable");
    }

    private static void assertMsg01(ResultActions result, String field, String message) throws Exception {
        String body = result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.messageCode").value("MSG01"))
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.errors." + field).value(message))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("java.", "Exception", "tools.jackson", "Instant", "Integer");
    }
}
