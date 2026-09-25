package com.cryptopilot.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With the switches the {@code prod} profile sets, the OpenAPI document and Swagger UI do not exist: the paths the
 * filter chain opens for reading answer 404, not a document and not a 401 (TECHNICAL_DESIGN 8).
 *
 * <p>Rule: TECHNICAL_DESIGN 8.
 */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OpenApiDisabledTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void TD8_switchedOff_theDocumentAndSwaggerUiAreNotServed() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }
}
