package com.cryptopilot.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cryptopilot.market.PairAnalysis;
import com.cryptopilot.trading.PlanContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** A snapshot is empty only without a pair, a plan and the performance; a request without history sends none. */
class AiModelTest {

    private static final Instant AT = Instant.parse("2026-11-07T10:00:00Z");

    @Test
    void T074_aSnapshotCarriesNothing_onlyWhenEveryPartIsMissing() {
        assertThat(new ContextSnapshot(1, AT, null, null, null).carriesNothing())
                .isTrue();
        assertThat(new ContextSnapshot(1, AT, mock(PairAnalysis.class), null, null).carriesNothing())
                .isFalse();
        assertThat(new ContextSnapshot(1, AT, null, mock(PlanContext.class), null).carriesNothing())
                .isFalse();
        assertThat(new ContextSnapshot(1, AT, null, null, mock(PerformanceContext.class)).carriesNothing())
                .isFalse();
    }

    @Test
    void T072_aRequestWithoutHistory_sendsAnEmptyOne() {
        AiChatRequest request = new AiChatRequest(
                "gemini-test", "prompt", new BigDecimal("0.3"), 512, null, "RSI?", null, Duration.ofSeconds(60));

        assertThat(request.history()).isEmpty();
    }
}
