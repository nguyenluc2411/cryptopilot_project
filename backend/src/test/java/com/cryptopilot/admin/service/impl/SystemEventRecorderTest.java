package com.cryptopilot.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.repository.SystemEventStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Recording a failure never fails the caller, and keeps only a short, single-line message.
 *
 * <p>Rule: UC-46.
 */
class SystemEventRecorderTest {

    private final SystemEventStore store = mock(SystemEventStore.class);
    private final SystemEventRecorder recorder = new SystemEventRecorder(
            store,
            mock(PlatformTransactionManager.class),
            Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void aLogThatCannotBeWritten_doesNotThrow() {
        doThrow(new DataAccessResourceFailureException("database down"))
                .when(store)
                .record(any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatCode(() -> recorder.record(
                        SystemEventCategory.SYSTEM_ERROR,
                        SystemEventSource.APPLICATION,
                        "GET /x",
                        new IllegalStateException("boom"),
                        "ref"))
                .doesNotThrowAnyException();
        assertThat(recorder.awaitWritten(Duration.ofSeconds(5))).isTrue();
    }

    /** The caller never waits for the database, and a full queue drops the failure instead of growing. */
    @Test
    void theCallerNeverWaits_andAFullQueueDrops() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
                    release.await(10, TimeUnit.SECONDS);
                    return null;
                })
                .when(store)
                .record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        long started = System.nanoTime();
        for (int i = 0; i < SystemEventRecorder.QUEUE_CAPACITY + 5; i++) {
            recorder.record(
                    SystemEventCategory.EXTERNAL_API_FAILURE,
                    SystemEventSource.MAIL,
                    "send",
                    new RuntimeException("down"),
                    null);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(recorder.droppedCount()).isBetween(4L, 5L);
        release.countDown();
        assertThat(recorder.awaitWritten(Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void theMessage_isOneLine_cutToTheColumn_andAbsentWhenBlank() {
        assertThat(SystemEventRecorder.message(new RuntimeException("a\r\n\tb ")))
                .isEqualTo("a b");
        assertThat(SystemEventRecorder.message(new RuntimeException("x".repeat(1500))))
                .hasSize(SystemEventRecorder.MESSAGE_LENGTH);
        assertThat(SystemEventRecorder.message(new RuntimeException(" "))).isNull();
        assertThat(SystemEventRecorder.message(new RuntimeException())).isNull();
    }

    @Test
    void theFingerprint_dependsOnEveryPartOnly() {
        String one = SystemEventRecorder.fingerprint(
                SystemEventCategory.SYSTEM_ERROR, SystemEventSource.APPLICATION, "GET /x", "E");
        assertThat(one)
                .hasSize(64)
                .isEqualTo(SystemEventRecorder.fingerprint(
                        SystemEventCategory.SYSTEM_ERROR, SystemEventSource.APPLICATION, "GET /x", "E"));
        assertThat(one)
                .isNotEqualTo(SystemEventRecorder.fingerprint(
                        SystemEventCategory.SYSTEM_ERROR, SystemEventSource.APPLICATION, "GET /y", "E"));
        assertThat(one)
                .isNotEqualTo(SystemEventRecorder.fingerprint(
                        SystemEventCategory.SYSTEM_ERROR, SystemEventSource.SCHEDULED_JOB, "GET /x", "E"));
    }
}
