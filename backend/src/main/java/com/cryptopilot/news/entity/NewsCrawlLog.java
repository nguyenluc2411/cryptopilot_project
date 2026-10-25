package com.cryptopilot.news.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.news.model.enums.CrawlStatus;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * One crawl of one source (NSF-10): when it ran, how it ended, how many items the feed held and how many became
 * articles, and the error when there was one. Written once, when the crawl of the source has finished, and never
 * changed; SCR-41 shows the log of each source (UC-49).
 *
 * <p>Rule: NSF-10; UC-49.
 */
@Getter
@Entity
@Table(name = "news_crawl_log")
@AttributeOverride(name = "id", column = @Column(name = "crawl_log_id", nullable = false, updatable = false))
public class NewsCrawlLog extends BaseEntity {

    /** The longest error text kept; a stack of causes beyond it says nothing an administrator can act on. */
    public static final int MAX_ERROR_LENGTH = 2000;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", updatable = false)
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "crawl_status", nullable = false, length = 32, updatable = false)
    private CrawlStatus status;

    @Column(name = "articles_found", updatable = false)
    private Integer articlesFound;

    @Column(name = "articles_inserted", updatable = false)
    private Integer articlesInserted;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    /** For JPA only. */
    protected NewsCrawlLog() {}

    /**
     * The record of a finished crawl. A FAILED crawl read no feed, so it carries no counts and needs an error; the
     * other two carry both counts, and a PARTIAL one says what went wrong.
     *
     * @param found the items the feed held, or {@code null} when it could not be read
     * @param inserted the items stored as new articles, at most {@code found}, or {@code null} with it
     * @param error what went wrong, cut to {@link #MAX_ERROR_LENGTH}; {@code null} for a SUCCESS
     */
    public static NewsCrawlLog record(
            UUID sourceId,
            Instant startedAt,
            Instant finishedAt,
            CrawlStatus status,
            Integer found,
            Integer inserted,
            String error) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("a crawl finishes after it starts");
        }
        boolean counted = found != null && inserted != null;
        if (status == CrawlStatus.FAILED ? found != null || inserted != null : !counted) {
            throw new IllegalArgumentException("a FAILED crawl has no counts, the others have both, was " + status
                    + " with found=" + found + " inserted=" + inserted);
        }
        if (counted && (inserted < 0 || inserted > found)) {
            throw new IllegalArgumentException("0 <= inserted <= found, was found=" + found + " inserted=" + inserted);
        }
        boolean hasError = error != null && !error.isBlank();
        if ((status == CrawlStatus.SUCCESS) == hasError) {
            throw new IllegalArgumentException("only a SUCCESS has no error, was " + status);
        }
        NewsCrawlLog log = new NewsCrawlLog();
        log.sourceId = sourceId;
        log.startedAt = startedAt;
        log.finishedAt = finishedAt;
        log.status = status;
        log.articlesFound = found;
        log.articlesInserted = inserted;
        log.errorMessage = hasError ? cut(error.strip()) : null;
        return log;
    }

    private static String cut(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
