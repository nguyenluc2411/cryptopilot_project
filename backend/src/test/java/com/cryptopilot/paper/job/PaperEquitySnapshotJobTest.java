package com.cryptopilot.paper.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.paper.config.PaperEquityProperties;
import com.cryptopilot.paper.service.PaperPnlService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * The daily equity snapshot and its retries: the accounts left without a snapshot are tried again every interval until
 * the cut-off time of the day (PT-R02).
 */
class PaperEquitySnapshotJobTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final PaperEquityProperties PROPERTIES =
            new PaperEquityProperties(true, "0 0 0 * * *", UTC, Duration.ofMinutes(5), LocalTime.of(23, 55));

    private final PaperPnlService pnl = mock(PaperPnlService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void PTR02_theDailyRunAndItsRetries_areScheduled() {
        job("2026-10-18T00:00:00Z").start();

        verify(scheduler).schedule(any(Runnable.class), any(CronTrigger.class));
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofMinutes(5)));
    }

    @Test
    void PTR02_aRetryBeforeTheCutOff_takesTheMissingSnapshots() {
        when(pnl.snapshotAll()).thenReturn(1);

        job("2026-10-18T23:54:59Z").retry();

        verify(pnl).snapshotAll();
    }

    @Test
    void PTR02_aRetryFromTheCutOff_takesNone() {
        job("2026-10-18T23:55:00Z").retry();

        verify(pnl, never()).snapshotAll();
    }

    @Test
    void PTR02_aFailingRetry_isLeftForTheNextOne() {
        when(pnl.snapshotAll()).thenThrow(new IllegalStateException("database down"));

        job("2026-10-18T08:00:00Z").retry();

        verify(pnl).snapshotAll();
    }

    private PaperEquitySnapshotJob job(String now) {
        return new PaperEquitySnapshotJob(pnl, scheduler, PROPERTIES, Clock.fixed(Instant.parse(now), UTC));
    }
}
