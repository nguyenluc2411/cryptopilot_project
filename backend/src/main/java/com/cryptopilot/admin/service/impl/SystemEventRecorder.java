package com.cryptopilot.admin.service.impl;

import com.cryptopilot.admin.model.enums.SystemEventCategory;
import com.cryptopilot.admin.model.enums.SystemEventSource;
import com.cryptopilot.admin.repository.SystemEventStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the system log of UC-46. A failure is written in a transaction of its own, so it stays when the command that
 * failed rolls back, and recording never throws: a log that cannot be written is reported in the application log and
 * the caller carries on with its own failure, never with this one. A failure raised while recording one (the database
 * itself is down) is not recorded again.
 *
 * <p>The same failure seen again within {@link #MERGE_WINDOW} is one row with a count, so a service down for an hour
 * leaves a few rows, not one per call. Only the exception's type and message are kept, cut to the column's length; no
 * request body, header or argument is.
 *
 * <p>Rule: UC-46; registration form, Admin System ("system errors, and external API failures").
 */
@Component
public class SystemEventRecorder {

    /** A repeat of a failure within this window adds to its row. */
    static final Duration MERGE_WINDOW = Duration.ofMinutes(10);

    static final int OPERATION_LENGTH = 200;
    static final int ERROR_TYPE_LENGTH = 200;
    static final int MESSAGE_LENGTH = 1000;
    static final int REFERENCE_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger(SystemEventRecorder.class);
    private static final ThreadLocal<Boolean> RECORDING = ThreadLocal.withInitial(() -> false);

    private final SystemEventStore store;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    SystemEventRecorder(SystemEventStore store, PlatformTransactionManager transactions, Clock clock) {
        this.store = store;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * Records one failure.
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
        if (RECORDING.get()) {
            return;
        }
        RECORDING.set(true);
        try {
            String op = cut(operation == null || operation.isBlank() ? "unknown" : operation, OPERATION_LENGTH);
            String type = cut(error.getClass().getName(), ERROR_TYPE_LENGTH);
            String message = message(error);
            String ref = reference == null ? null : cut(reference, REFERENCE_LENGTH);
            Instant now = clock.instant();
            ownTransaction.executeWithoutResult(status -> store.record(
                    category,
                    source,
                    op,
                    type,
                    message,
                    ref,
                    fingerprint(category, source, op, type),
                    now,
                    now.minus(MERGE_WINDOW)));
        } catch (RuntimeException failed) {
            log.warn("Could not write the system log for {} {}: {}", source, operation, failed.toString());
        } finally {
            RECORDING.remove();
        }
    }

    /** The message on one line, without control characters, cut to the column; {@code null} when there is none. */
    static String message(Throwable error) {
        String text = error.getMessage();
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.replaceAll("\\p{Cntrl}+", " ").strip();
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
