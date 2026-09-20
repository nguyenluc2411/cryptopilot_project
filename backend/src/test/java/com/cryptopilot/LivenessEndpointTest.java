package com.cryptopilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The liveness probe answers only once the whole application context is up, and since T-005 that
 * includes a data source and an applied migration. The container therefore belongs to this test as
 * much as to the schema tests: without it the probe would be reporting on an application that
 * cannot start.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class LivenessEndpointTest {

    @Autowired
    private MockMvcTester mockMvc;

    @Test
    void liveness_whenApplicationStarts_returnsUp() {
        assertThat(mockMvc.get().uri("/actuator/health/liveness"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }
}
