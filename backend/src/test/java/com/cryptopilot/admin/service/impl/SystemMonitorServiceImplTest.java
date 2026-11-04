package com.cryptopilot.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.admin.model.enums.ServiceStatus;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
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
        assertThat(SystemMonitorServiceImpl.status(
                        SystemEventSource.AI_SERVICE, ago(answerMinutes), ago(failureMinutes), NOW))
                .isEqualTo(expected);
    }

    /** The news sites: one feed refusing while another answered recently is DEGRADED, none answering DOWN. */
    @ParameterizedTest(name = "news: answer {0} min ago, failure {1} min ago → {2}")
    @CsvSource({
        " 5,  1, DEGRADED",
        " 1,  5, DEGRADED",
        "16,  1, DOWN",
        "  ,  1, DOWN",
        " 1,   , UP",
        "  ,   , UNKNOWN",
    })
    void theNewsSites_areDownOnlyWhenNoneAnswered(
            Integer answerMinutes, Integer failureMinutes, ServiceStatus expected) {
        assertThat(SystemMonitorServiceImpl.status(
                        SystemEventSource.NEWS_SOURCE, ago(answerMinutes), ago(failureMinutes), NOW))
                .isEqualTo(expected);
    }

    /** A healthy feed closes a 15m candle every 15 minutes, so with a grace of 2 it is stale only after 17. */
    @ParameterizedTest(name = "latest 15m candle closed {0} min ago → stale {1}")
    @CsvSource({" 0, false", "12, false", "17, false", "18, true", "  , true"})
    void UC46_aFeed_isStaleOnlyAfterItsShortestTimeframeAndTheGrace(Integer closedMinutes, boolean expected) {
        Duration staleAfter = SystemMonitorServiceImpl.staleAfter(Duration.ofMinutes(15), Duration.ofMinutes(2));

        assertThat(staleAfter).isEqualTo(Duration.ofMinutes(17));
        assertThat(SystemMonitorServiceImpl.stale(ago(closedMinutes), NOW, staleAfter))
                .isEqualTo(expected);
    }

    @Test
    void UC46_withoutAShortestTimeframe_theGraceAloneApplies() {
        assertThat(SystemMonitorServiceImpl.staleAfter(null, Duration.ofMinutes(2)))
                .isEqualTo(Duration.ofMinutes(2));
    }

    private static Instant ago(Integer minutes) {
        return minutes == null ? null : NOW.minus(Duration.ofMinutes(minutes));
    }
}
