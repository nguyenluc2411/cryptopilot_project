package com.cryptopilot.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.admin.model.enums.ServiceStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The status of an external service, over every combination of its latest answer and latest failure (minutes before
 * now; empty for none).
 *
 * <p>Rule: UC-46.
 */
class SystemMonitorServiceImplTest {

    private static final Instant NOW = Instant.parse("2031-01-01T12:00:00Z");

    @ParameterizedTest(name = "answer {0} min ago, failure {1} min ago → {2}")
    @CsvSource({
        ",   , UNKNOWN",
        ",  1, DOWN",
        ", 15, DOWN",
        ", 16, UNKNOWN",
        " 5,  1, DOWN",
        " 1,  5, DEGRADED",
        " 1,   , UP",
        " 1, 60, UP",
        " 0,  0, DOWN",
    })
    void theStatus_weighsTheLatestFailure_againstTheLatestAnswer(
            Integer answerMinutes, Integer failureMinutes, ServiceStatus expected) {
        assertThat(SystemMonitorServiceImpl.status(ago(answerMinutes), ago(failureMinutes), NOW))
                .isEqualTo(expected);
    }

    private static Instant ago(Integer minutes) {
        return minutes == null ? null : NOW.minus(Duration.ofMinutes(minutes));
    }
}
