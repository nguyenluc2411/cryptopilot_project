package com.cryptopilot.news.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.news.config.NewsCrawlProperties;
import com.cryptopilot.news.model.CrawlRun;
import com.cryptopilot.news.service.CrawlService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

/**
 * The schedule of NSF-10: nothing without the switch, a fixed delay after each run with it, and a failed run that
 * leaves the schedule alive.
 *
 * <p>Rule: NSF-10.
 */
class NewsCrawlJobTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final CrawlService crawl = mock(CrawlService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void NSF10_withoutTheSwitch_nothingIsScheduled() {
        job(false).start();

        verifyNoInteractions(scheduler, crawl);
    }

    @Test
    void NSF10_withTheSwitch_eachRunFollowsThePreviousOneByThePeriod() {
        job(true).start();

        verify(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), eq(NOW.plusSeconds(60)), eq(Duration.ofMinutes(5)));
    }

    @Test
    void NSF10_aRunThatFails_doesNotEndTheSchedule() {
        when(crawl.crawlDue()).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> job(true).run()).doesNotThrowAnyException();
    }

    @Test
    void NSF10_aRun_crawlsTheDueSources() {
        when(crawl.crawlDue()).thenReturn(new CrawlRun(2, 1, 7), CrawlRun.NOTHING_DUE);
        NewsCrawlJob job = job(true);

        job.run();
        job.run();

        verify(crawl, times(2)).crawlDue();
    }

    private NewsCrawlJob job(boolean enabled) {
        return new NewsCrawlJob(
                crawl,
                scheduler,
                new NewsCrawlProperties(
                        enabled,
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(1),
                        Duration.ofHours(48),
                        5,
                        Duration.ofMinutes(1),
                        null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
