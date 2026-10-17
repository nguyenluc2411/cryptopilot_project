package com.cryptopilot.common.service.impl;

import com.cryptopilot.common.config.OutboxProperties;
import com.cryptopilot.common.service.OutboxService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

/**
 * The outbox processor over the Spring Modulith event publication registry (T-056).
 *
 * <p>The registry writes one row per {@code @ApplicationModuleListener} in the transaction that publishes the event,
 * and deletes it once the listener returns (completion mode {@code delete}). A listener that throws leaves its row
 * {@code FAILED}; this class resubmits such a row on its first run at least 1, 2, 4 ... times
 * {@code first-retry-after} after the row's last attempt, at most {@code max-retries} times. A row still
 * {@code PUBLISHED}, {@code PROCESSING} or {@code RESUBMITTED} long after its last attempt belongs to a crashed instance
 * and is counted as failed first, which is how a restart picks it up.
 *
 * <p>A delivery that has used all its retries is left alone and reported once, by a WARN naming the event type, the
 * listener and the publication id, never the payload.
 *
 * <p>Rows older than the retention are deleted whatever their state. The auth events carry a working link (A-18), so
 * an undeliverable row must not keep one in the table after the link itself has expired.
 *
 * <p>The two statements on {@code event_publication} use the registry's v2 columns, which V1 creates; Modulith offers
 * no API to fail a stale row by its last attempt or to delete an unfinished one.
 *
 * <p>Rule: NSF-15; ADR-010; D-08; A-18.
 *
 * <p>Reference: Richardson, C. (2018). <i>Microservices Patterns</i>. Manning, ch. 3 (Transactional Outbox).
 * <p>Reference: Hohpe, G. &amp; Woolf, B. (2003). <i>Enterprise Integration Patterns</i>. Addison-Wesley (Guaranteed
 * Delivery).
 * <p>Reference: Spring Modulith Reference Documentation 2.1, Event Publication Registry.
 */
@Service
public class OutboxServiceImpl implements OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxServiceImpl.class);

    private final IncompleteEventPublications publications;
    private final JdbcClient sql;
    private final Clock clock;
    private final OutboxProperties properties;

    /** The exhausted deliveries already reported, so each is logged once per instance rather than on every run. */
    private final Set<UUID> reportedGiveUps = ConcurrentHashMap.newKeySet();

    public OutboxServiceImpl(
            IncompleteEventPublications publications, JdbcClient sql, Clock clock, OutboxProperties properties) {
        this.publications = publications;
        this.sql = sql;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public int retryFailedDeliveries() {
        Instant now = clock.instant();
        int stale = sql.sql("""
                        update event_publication set status = 'FAILED'
                         where completion_date is null
                           and status in ('PUBLISHED', 'PROCESSING', 'RESUBMITTED')
                           and coalesce(last_resubmission_date, publication_date) < :cutoff
                        """)
                .param("cutoff", Timestamp.from(now.minus(properties.staleAfter())))
                .update();
        if (stale > 0) {
            log.warn("Outbox: {} deliveries unfinished for over {} counted as failed", stale, properties.staleAfter());
        }

        AtomicInteger due = new AtomicInteger();
        Set<UUID> seen = new HashSet<>();
        publications.resubmitIncompletePublications(publication -> {
            seen.add(publication.getIdentifier());
            if (isExhausted(publication, properties) && reportedGiveUps.add(publication.getIdentifier())) {
                reportGiveUp(publication);
            }
            boolean retry = isDue(publication, now, properties);
            if (retry) {
                due.incrementAndGet();
            }
            return retry;
        });
        reportedGiveUps.retainAll(seen);
        if (due.get() > 0) {
            // Counted before Modulith claims each row; with one instance (TD 1.3) every due row is claimed.
            log.info("Outbox: {} failed deliveries due for a retry, resubmitting", due.get());
        }
        return due.get();
    }

    /** Names the row by its id, event type and listener, read from the table; the payload is never read for it. */
    private void reportGiveUp(EventPublication publication) {
        sql.sql("select event_type, listener_id from event_publication where id = :id")
                .param("id", publication.getIdentifier())
                .query((row, n) -> new String[] {row.getString("event_type"), row.getString("listener_id")})
                .optional()
                .ifPresent(row -> log.warn(
                        "Outbox: giving up on publication {} of {} for {} after {} attempts; deleted after {}",
                        publication.getIdentifier(),
                        row[0],
                        row[1],
                        publication.getCompletionAttempts(),
                        properties.retention()));
    }

    @Override
    public int deleteExpired() {
        int deleted = sql.sql("delete from event_publication where publication_date < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(properties.retention())))
                .update();
        if (deleted > 0) {
            log.warn("Outbox: deleted {} undelivered publications older than {}", deleted, properties.retention());
        }
        return deleted;
    }

    /**
     * Whether a publication is a failed delivery whose next retry is due. The first attempt counts as one, so a
     * publication with {@code n} attempts has been retried {@code n - 1} times and waits
     * {@code firstRetryAfter * 2^(n - 1)} after its last one.
     */
    /** Whether a publication failed on its last allowed attempt. */
    static boolean isExhausted(EventPublication publication, OutboxProperties properties) {
        return publication.getStatus() == EventPublication.Status.FAILED
                && Math.max(1, publication.getCompletionAttempts()) > properties.maxRetries();
    }

    static boolean isDue(EventPublication publication, Instant now, OutboxProperties properties) {
        if (publication.getStatus() != EventPublication.Status.FAILED) {
            return false;
        }
        int attempts = Math.max(1, publication.getCompletionAttempts());
        if (attempts > properties.maxRetries()) {
            return false;
        }
        Instant lastAttempt = publication.getLastResubmissionDate() != null
                ? publication.getLastResubmissionDate()
                : publication.getPublicationDate();
        Duration wait = properties.firstRetryAfter().multipliedBy(1L << (attempts - 1));
        return !now.isBefore(lastAttempt.plus(wait));
    }
}
