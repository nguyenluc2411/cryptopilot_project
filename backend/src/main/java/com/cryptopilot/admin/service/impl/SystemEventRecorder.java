package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.repository.SystemEventStore;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the system log of UC-46. The caller only hands the failure over: it is written on a writer thread of its own,
 * in a transaction of its own, so
 *
 * <ul>
 *   <li>it stays when the command that failed rolls back,
 *   <li>the caller never waits for a database connection — a failure caused by an exhausted pool would otherwise wait
 *       the pool's timeout (30 s) before its 500 is answered — and never holds a second connection while it keeps its
 *       own,
 *   <li>and recording never throws: a failure that cannot be written is reported in the application log, and when
 *       {@link #QUEUE_CAPACITY} failures are already waiting (the database is down, everything fails) the new one is
 *       dropped and counted rather than piled up in memory.
 * </ul>
 *
 * <p>The same failure seen again within {@link #MERGE_WINDOW} of its row's first failure is one row with a count, so a
 * service down for an hour leaves six rows, not one per call. Only the exception's type and message are kept, cut to
 * the column's length, with emails and secrets masked ({@link AuditValueRedactor#redactText}); no request body, header or
 * argument is.
 *
 * <p>Known limitation: one instance (D-39); two instances writing the same failure at once may open two rows of one
 * bucket.
 *
 * <p>Rule: UC-46; registration form, Admin System ("system errors, and external API failures"). Reference: Nygard, M.
 * <i>Release It!</i> 2nd ed., 2018, ch. 5 (stability patterns, failure aggregation).
 */
@Component
public class SystemEventRecorder {

    /** A repeat of a failure within this window of its row's first failure adds to that row. */
    static final Duration MERGE_WINDOW = Duration.ofMinutes(10);

    /** How many failures may wait for the writer; more are dropped. */
    static final int QUEUE_CAPACITY = 1000;

    /** How long shutdown waits for the queue to be written. */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    static final int OPERATION_LENGTH = 200;
    static final int ERROR_TYPE_LENGTH = 200;
    static final int MESSAGE_LENGTH = 1000;
    static final int REFERENCE_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger(SystemEventRecorder.class);

    private final SystemEventStore store;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;
    private final ThreadPoolExecutor writer;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lostAtShutdown = new AtomicLong();
    /** Failures handed over and not written yet. */
    private final AtomicLong pending = new AtomicLong();

    SystemEventRecorder(SystemEventStore store, PlatformTransactionManager transactions, Clock clock) {
        this.store = store;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.writer = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                task -> {
                    Thread thread = new Thread(task, "system-log-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Hands one failure to the writer; returns at once.
     *
     * @param operation what was being done: a request's method and path, a job's class and method, a client's method
     * @param reference the correlation id the caller was answered with, or {@code null}
     */
    public void record(
            SystemEventCategory category,
            SystemEventSource source,
            String operation,
            Throwable error,
            String reference) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(error, "error must not be null");
        if (Thread.currentThread().getName().equals("system-log-writer")) {
            // The writer's own failure (the database is down): logged below, never recorded again.
            return;
        }
        String op = cut(operation == null || operation.isBlank() ? "unknown" : operation, OPERATION_LENGTH);
        String type = cut(error.getClass().getName(), ERROR_TYPE_LENGTH);
        String message = message(error);
        String ref = reference == null ? null : cut(reference, REFERENCE_LENGTH);
        Instant now = clock.instant();
        pending.incrementAndGet();
        try {
            writer.execute(() -> {
                try {
                    write(category, source, op, type, message, ref, now);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException full) {
            pending.decrementAndGet();
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                log.warn("System log queue full: {} failures not recorded so far (latest {} {})", count, source, op);
            }
        }
    }

    private void write(
            SystemEventCategory category,
            SystemEventSource source,
            String operation,
            String errorType,
            String message,
            String reference,
            Instant seenAt) {
        try {
            ownTransaction.executeWithoutResult(status -> store.record(
                    category,
                    source,
                    operation,
                    errorType,
                    message,
                    reference,
                    fingerprint(category, source, operation, errorType),
                    seenAt,
                    seenAt.minus(MERGE_WINDOW)));
        } catch (RuntimeException failed) {
            log.warn("Could not write the system log for {} {}: {}", source, operation, failed.toString());
        }
    }

    /**
     * Waits until every failure handed over so far is written, at most {@code timeout}; for tests and for shutdown.
     *
     * @return whether the queue emptied in time
     */
    public boolean awaitWritten(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** How many failures were dropped because the queue was full, since the start. */
    public long droppedCount() {
        return dropped.get();
    }

    /** How many failures were still waiting when the writer stopped, since the start. */
    public long lostAtShutdownCount() {
        return lostAtShutdown.get();
    }

    /** Writes what is waiting, a few seconds at most, then stops the writer and reports what was left. */
    @PreDestroy
    void stop() {
        stop(SHUTDOWN_WAIT);
    }

    void stop(Duration wait) {
        awaitWritten(wait);
        int left = writer.shutdownNow().size();
        if (left > 0) {
            pending.addAndGet(-left);
            lostAtShutdown.addAndGet(left);
            log.warn("System log writer stopped: {} failures not recorded", left);
        }
    }

    /** The message on one line, without control characters, cut to the column; {@code null} when there is none. */
    static String message(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = AuditValueRedactor.redactText(text.replaceAll("\\p{Cntrl}+", " "))
                .strip();
        return clean.isEmpty() ? null : cut(clean, MESSAGE_LENGTH);
    }

    static String fingerprint(
            SystemEventCategory category, SystemEventSource source, String operation, String errorType) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(
                    (category + "|" + source + "|" + operation + "|" + errorType).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM provides SHA-256", impossible);
        }
    }

    private static String cut(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }
}
