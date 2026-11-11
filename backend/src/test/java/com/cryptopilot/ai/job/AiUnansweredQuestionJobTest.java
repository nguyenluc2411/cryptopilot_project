package com.cryptopilot.ai.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cryptopilot.ai.config.AiAssistantProperties;
import com.cryptopilot.ai.service.AiAnswerService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/** The NSF-16 sweep is scheduled only when it is enabled. */
class AiUnansweredQuestionJobTest {

    private final AiAnswerService answers = mock(AiAnswerService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    private static AiAssistantProperties properties(boolean sweepEnabled) {
        return new AiAssistantProperties(10, 2000, Duration.ofSeconds(60), sweepEnabled, Duration.ofSeconds(15));
    }

    @Test
    void NSF16_aDisabledSweep_isNotScheduled() {
        new AiUnansweredQuestionJob(answers, scheduler, properties(false)).start();

        verifyNoInteractions(scheduler);
    }

    @Test
    void NSF16_anEnabledSweep_runsAtItsInterval() {
        new AiUnansweredQuestionJob(answers, scheduler, properties(true)).start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(15)));
    }
}
