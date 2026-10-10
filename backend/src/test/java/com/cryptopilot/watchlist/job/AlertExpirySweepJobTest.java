package com.cryptopilot.watchlist.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.watchlist.config.AlertEngineProperties;
import com.cryptopilot.watchlist.service.AlertExpiryService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;

/**
 * The BR-19 expiry sweep job: it registers its cron when enabled, nothing when disabled, and each run goes through
 * {@link AlertExpiryService}; a failing run stays inside the job. The scheduler is a test double, so no Spring
 * scheduling runs here.
 *
 * <p>Rule: BR-19; SRS 3.4.4.
 *
 * <p>Reference: Meszaros, G. (2007). <i>xUnit Test Patterns</i>. Addison-Wesley (Humble Object: the job only
 * orchestrates, the rule is tested in the service).
 */
class AlertExpirySweepJobTest {

    private final AlertExpiryService expiry = mock(AlertExpiryService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void BR19_enabled_registersTheConfiguredCron_andEachTickSweepsThroughTheService() {
        job(true).start();

        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).schedule(tick.capture(), trigger.capture());
        assertThat(trigger.getValue())
                .isInstanceOfSatisfying(CronTrigger.class, cron -> assertThat(cron.getExpression())
                        .isEqualTo("0 * * * * *"));

        tick.getValue().run();
        tick.getValue().run();
        verify(expiry, times(2)).expireDue();
    }

    @Test
    void BR19_disabled_schedulesNothing() {
        job(false).start();

        verifyNoInteractions(scheduler, expiry);
    }

    @Test
    void BR19_aRun_expiresThroughTheService() {
        when(expiry.expireDue()).thenReturn(2);

        job(true).run();

        verify(expiry).expireDue();
    }

    @Test
    void BR19_aFailingRun_isContained_andTheNextRunTriesAgain() {
        when(expiry.expireDue())
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(1);
        AlertExpirySweepJob job = job(true);

        assertThatCode(job::run).doesNotThrowAnyException();
        job.run();

        verify(expiry, times(2)).expireDue();
    }

    private AlertExpirySweepJob job(boolean enabled) {
        return new AlertExpirySweepJob(expiry, scheduler, properties(enabled));
    }

    private static AlertEngineProperties properties(boolean sweep) {
        return new AlertEngineProperties(
                false,
                1,
                1000,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                new AlertEngineProperties.ExpirySweep(sweep, "0 * * * * *", "UTC"));
    }
}
