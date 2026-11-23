package com.cryptopilot.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.notification.client.PushResult;
import com.cryptopilot.notification.client.PushSender;
import com.cryptopilot.notification.job.PushNotificationListener;
import com.cryptopilot.notification.model.NotificationWritten;
import com.cryptopilot.notification.model.enums.NotificationType;
import com.cryptopilot.notification.model.enums.PushOutcome;
import com.cryptopilot.notification.service.impl.PushDispatcher;
import com.cryptopilot.user.UserApi;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The push bulkhead (T-058, NSF-15).
 *
 * <p>Invariants: (1) a dispatch runs on a {@code cp-push-} thread, never on the listener's; (2) a slow or failing push
 * service does not delay another module listener on the application's executor; (3) the listener returns only after
 * its dispatch did, so its publication completes after the push was tried; (4) a full pool refuses, so the publication
 * stays incomplete; (5) a failure of the dispatch reaches the listener.
 *
 * <p>Rule: NSF-15; ADR-010.
 */
class PushExecutorTest {

    private static final UUID TRADER = UUID.fromString("019b76da-a800-7000-8000-00000000e101");

    /** Stands in for Spring Boot's applicationTaskExecutor with virtual threads. */
    private final ExecutorService listeners = Executors.newVirtualThreadPerTaskExecutor();

    private final PushExecutor pool = new PushExecutor(1, 1);

    @AfterEach
    void close() {
        pool.close();
        listeners.shutdownNow();
    }

    @Test
    void NSF15_aSlowPushService_doesNotDelayAnotherListener_andTheListenerWaitsForItsDispatch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> sentOn = new AtomicReference<>();
        PushSender slow = message -> {
            sentOn.set(Thread.currentThread().getName());
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return PushResult.of(PushOutcome.DELIVERED);
        };
        PushNotificationListener listener = new PushNotificationListener(dispatcher(slow), pool);

        CompletableFuture<Void> push = CompletableFuture.runAsync(() -> listener.push(written()), listeners);
        CompletableFuture<String> mail = CompletableFuture.supplyAsync(() -> "mail sent", listeners);

        assertThat(mail.get(2, TimeUnit.SECONDS)).isEqualTo("mail sent");
        Thread.sleep(100);
        assertThat(push).as("the listener waits for its dispatch").isNotDone();
        release.countDown();
        push.get(5, TimeUnit.SECONDS);
        assertThat(sentOn.get()).startsWith("cp-push-");
    }

    @Test
    void NSF15_aFullPool_refuses_soThePublicationStaysIncomplete() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Integer> running = CompletableFuture.supplyAsync(
                () -> pool.run(() -> {
                    started.countDown();
                    awaitQuietly(release);
                    return 1;
                }),
                listeners);
        started.await(5, TimeUnit.SECONDS);
        CompletableFuture<Integer> queued = CompletableFuture.supplyAsync(() -> pool.run(() -> 2), listeners);
        Thread.sleep(100);

        assertThatExceptionOfType(RejectedExecutionException.class).isThrownBy(() -> pool.run(() -> 3));

        release.countDown();
        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(queued.get(5, TimeUnit.SECONDS)).isEqualTo(2);
    }

    @Test
    void NSF15_aFailingDispatch_failsTheListener() {
        assertThatIllegalStateException()
                .isThrownBy(() -> pool.run(() -> {
                    throw new IllegalStateException("user module down");
                }))
                .withMessage("user module down");
        assertThatIllegalStateException()
                .isThrownBy(() -> pool.run(() -> {
                    throw new AssertionError("an error");
                }))
                .withCauseInstanceOf(AssertionError.class);
    }

    @Test
    void NSF15_anInterruptedListener_cancelsItsDispatch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptKept = new AtomicReference<>();
        Thread listener = Thread.ofVirtual().start(() -> {
            try {
                pool.run(() -> {
                    awaitQuietly(release);
                    return 1;
                });
            } catch (RuntimeException failure) {
                thrown.set(failure);
                interruptKept.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread.sleep(100);

        listener.interrupt();
        listener.join(5000);

        assertThat(thrown.get()).isInstanceOf(CancellationException.class);
        assertThat(interruptKept.get()).isTrue();
        release.countDown();
    }

    private PushDispatcher dispatcher(PushSender sender) {
        UserApi users = mock(UserApi.class);
        when(users.pushTokens(any())).thenReturn(List.of("t1"));
        return new PushDispatcher(
                users,
                mock(EntitlementApi.class),
                sender,
                new PushProperties(
                        "", "", "https://fcm.example.test", 4, Duration.ofMillis(1), Duration.ofSeconds(1), 1, 1));
    }

    private static NotificationWritten written() {
        return new NotificationWritten(
                UUID.randomUUID(), TRADER, NotificationType.SYSTEM, "Title", "Content", null, null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
