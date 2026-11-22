package com.cryptopilot.notification.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bulkhead of push delivery (T-058, NSF-15): a small pool of its own ({@code cp-push-N} threads, {@code poolSize}
 * of them, at most {@code queueCapacity} dispatches waiting), on which every call to the Push Notification Service and
 * every wait between its retries runs. An FCM outage then fills this pool only; the module listeners of mail (the
 * links of sign-up and password reset), alerts and the rest keep their own threads.
 *
 * <p>{@link #run} hands the dispatch over and waits for it, so the listener, and with it the event publication it
 * completes, ends only once the dispatch returned (the outbox of ADR-010 is kept). The waiting listener runs on the
 * application's task executor, one virtual thread per task ({@code spring.threads.virtual.enabled}), so the wait holds
 * no platform thread.
 *
 * <p>Full, the pool refuses (drop with a warning): {@link #run} throws, the listener fails, and its publication stays
 * incomplete in the registry, to be submitted again by the outbox job ({@code common.job.OutboxProcessorJob}) rather
 * than lost or run on the caller's thread.
 *
 * <p>Not an {@link java.util.concurrent.Executor} bean on purpose: one would make Spring Boot drop its own
 * {@code applicationTaskExecutor}, which every other {@code @Async} listener runs on.
 *
 * <p>Rule: NSF-15; ADR-010.
 *
 * <p>Reference: Nygard, M. T. (2018). <i>Release It!</i> (2nd ed.). Pragmatic Bookshelf, ch. 5 "Bulkheads" (a
 * failing dependency exhausts only the resources set apart for it) and "Timeouts".
 */
public class PushExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PushExecutor.class);

    private final ThreadPoolExecutor pool;

    public PushExecutor(int poolSize, int queueCapacity) {
        AtomicInteger next = new AtomicInteger();
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "cp-push-" + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.pool = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threads,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Runs the dispatch on the push pool and waits for its answer.
     *
     * @throws RejectedExecutionException when the pool and its queue are full; the push is not sent now
     */
    public <T> T run(Supplier<T> dispatch) {
        Future<T> answer;
        try {
            answer = pool.submit(dispatch::get);
        } catch (RejectedExecutionException full) {
            log.warn(
                    "NSF-15 push pool full ({} waiting): the push is left to a resubmission",
                    pool.getQueue().size());
            throw full;
        }
        try {
            return answer.get();
        } catch (InterruptedException interrupted) {
            answer.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("the push dispatch was interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(failed.getCause());
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
