package com.cryptopilot.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.service.impl.SystemEventRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A scheduled job that throws is recorded under its own name, the {@code @Scheduled} method, whatever it failed on.
 *
 * <p>Rule: UC-46.
 */
class SystemLogConfigTest {

    @Test
    void theJob_isTheOutermostApplicationFrame() {
        RuntimeException failure = new RuntimeException("x");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.cryptopilot.market.repository.OhlcvRepository", "save", "F.java", 1),
            new StackTraceElement("com.cryptopilot.market.job.CandleBackfillJob$$SpringCGLIB$$0", "run", "F.java", 2),
            new StackTraceElement("com.cryptopilot.market.job.CandleBackfillJob", "run", "F.java", 3),
            new StackTraceElement("org.springframework.scheduling.support.ScheduledMethodRunnable", "run", "S", 4)
        });

        assertThat(SystemLogConfig.jobOf(failure)).isEqualTo("CandleBackfillJob.run");
        assertThat(SystemLogConfig.jobOf(new RuntimeException() {
                    {
                        setStackTrace(new StackTraceElement[0]);
                    }
                }))
                .isEqualTo("unknown");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aJobFailure_isRecordedAsASystemError() {
        SystemEventRecorder recorder = mock(SystemEventRecorder.class);
        ObjectProvider<SystemEventRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        RuntimeException failure = new RuntimeException("job failed");

        SystemLogConfig.jobFailures(provider).handleError(failure);

        verify(recorder)
                .record(
                        eq(SystemEventCategory.SYSTEM_ERROR),
                        eq(SystemEventSource.SCHEDULED_JOB),
                        eq("SystemLogConfigTest.aJobFailure_isRecordedAsASystemError"),
                        same(failure),
                        isNull());
    }
}
