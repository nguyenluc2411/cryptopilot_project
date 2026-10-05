package com.cryptopilot.auth.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.auth.config.TokenSweepProperties;
import com.cryptopilot.auth.service.TokenRetentionService;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;

/**
 * The NSF-17 refresh-token sweep: scheduled daily when enabled, nothing when disabled, and a failing run does not
 * escape into the scheduler.
 *
 * <p>Rule: NSF-17.
 */
class TokenSweepJobTest {

    private final TokenRetentionService retention = mock(TokenRetentionService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    @Test
    void NSF17_enabled_registersTheDailyCron() {
        job(true).start();

        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).schedule(any(Runnable.class), trigger.capture());
        assertThat(trigger.getValue())
                .isInstanceOfSatisfying(CronTrigger.class, cron -> assertThat(cron.getExpression())
                        .isEqualTo("0 15 0 * * *"));
    }

    @Test
    void NSF17_disabled_schedulesNothing() {
        job(false).start();

        verifyNoInteractions(scheduler);
    }

    @Test
    void NSF17_aRun_deletesThroughTheService() {
        when(retention.removeExpiredRefreshTokens()).thenReturn(3);

        job(true).run();

        verify(retention).removeExpiredRefreshTokens();
    }

    @Test
    void NSF17_aFailingRun_isContained() {
        when(retention.removeExpiredRefreshTokens()).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> job(true).run()).doesNotThrowAnyException();
    }

    private TokenSweepJob job(boolean enabled) {
        return new TokenSweepJob(
                retention, scheduler, new TokenSweepProperties(enabled, "0 15 0 * * *", ZoneOffset.UTC));
    }
}
