package com.cryptopilot.news.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.news.config.NewsSummaryProperties;
import com.cryptopilot.news.model.SummaryRun;
import com.cryptopilot.news.service.NewsSummaryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.TaskScheduler;

/**
 * The schedule of NSF-11: nothing without the switch, a fixed delay after each run with it, and a failed run that
 * leaves the schedule alive.
 *
 * <p>Rule: NSF-11.
 */
class NewsSummaryJobTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final NewsSummaryService summaries = mock(NewsSummaryService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void NSF11_withoutTheSwitch_nothingIsScheduled() {
        job(false).start();

        verifyNoInteractions(scheduler, summaries);
    }

    @Test
    void NSF11_withTheSwitch_eachRunFollowsThePreviousOneByThePeriod() {
        job(true).start();

        verify(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), eq(NOW.plusSeconds(120)), eq(Duration.ofMinutes(5)));
    }

    @Test
    void NSF11_aRunThatFails_doesNotEndTheSchedule_andARunSummarizes() {
        when(summaries.summarizeDue())
                .thenThrow(new IllegalStateException("prompt missing"))
                .thenReturn(new SummaryRun(3, 2, 1), SummaryRun.NOTHING_DUE);
        NewsSummaryJob job = job(true);

        assertThatCode(job::run).doesNotThrowAnyException();
        job.run();
        job.run();

        verify(summaries, times(3)).summarizeDue();
    }

    private NewsSummaryJob job(boolean enabled) {
        return new NewsSummaryJob(
                summaries,
                scheduler,
                new NewsSummaryProperties(
                        enabled,
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2),
                        10,
                        Duration.ofDays(7),
                        3,
                        "test-model",
                        0.2,
                        512,
                        "Vietnamese",
                        60,
                        30,
                        1200,
                        80,
                        new ByteArrayResource(new byte[0])),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
