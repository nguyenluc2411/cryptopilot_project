package com.cryptopilot.news.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.news.model.NewsSourceSettings;
import com.cryptopilot.news.model.enums.SourceType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * An RSS source the crawl reads (UC-49, SCR-41): its address and interval, whether it is read at all, and the
 * per-outlet settings that tell the crawl how to read its pages. {@code managedBy} is the administrator who last saved
 * it.
 *
 * <p>A source is due when it is active and its interval has elapsed since it was last read (BR-49, NSF-10); a source
 * never read is due at once.
 *
 * <p>Rule: UC-49; BR-49; NSF-10.
 */
@Getter
@Entity
@Table(name = "news_source")
@AttributeOverride(name = "id", column = @Column(name = "source_id", nullable = false, updatable = false))
public class NewsSource extends BaseEntity {

    @Column(name = "managed_by")
    private UUID managedBy;

    @Column(name = "source_name", nullable = false, length = 100)
    private String sourceName;

    @Column(name = "feed_url", nullable = false, length = 1000)
    private String feedUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private SourceType sourceType;

    @Column(name = "crawl_interval_minutes", nullable = false)
    private int crawlIntervalMinutes;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "last_crawled_at")
    private Instant lastCrawledAt;

    @Column(name = "category_filter", length = 256)
    private String categoryFilter;

    @Column(name = "body_selector", length = 512)
    private String bodySelector;

    @Column(name = "excluded_selectors", length = 1024)
    private String excludedSelectors;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    /** For JPA only. */
    protected NewsSource() {}

    /** A new RSS source with the administrator's settings, never read yet. */
    public static NewsSource create(NewsSourceSettings settings, UUID adminId) {
        NewsSource source = new NewsSource();
        source.sourceType = SourceType.RSS;
        source.apply(settings, adminId);
        return source;
    }

    /** Replaces every setting with the administrator's (UC-49); when it was last read is kept. */
    public void update(NewsSourceSettings settings, UUID adminId) {
        apply(settings, adminId);
    }

    /**
     * Whether the crawl should read the source at {@code now}: active, and never read or read at least one interval
     * ago (BR-49).
     */
    public boolean isDue(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return active
                && (lastCrawledAt == null
                        || !now.isBefore(lastCrawledAt.plus(Duration.ofMinutes(crawlIntervalMinutes))));
    }

    /** Records that the crawl read the source at {@code at}, so the next read waits one interval from then. */
    public void markCrawled(Instant at) {
        this.lastCrawledAt = Objects.requireNonNull(at, "at must not be null");
    }

    /** The settings as an administrator sees them, to compare before and after a change. */
    public NewsSourceSettings settings() {
        return new NewsSourceSettings(
                sourceName,
                feedUrl,
                crawlIntervalMinutes,
                active,
                categoryFilter,
                bodySelector,
                excludedSelectors,
                userAgent);
    }

    private void apply(NewsSourceSettings settings, UUID adminId) {
        Objects.requireNonNull(settings, "settings must not be null");
        this.sourceName = settings.sourceName();
        this.feedUrl = settings.feedUrl();
        this.crawlIntervalMinutes = settings.crawlIntervalMinutes();
        this.active = settings.active();
        this.categoryFilter = settings.categoryFilter();
        this.bodySelector = settings.bodySelector();
        this.excludedSelectors = settings.excludedSelectors();
        this.userAgent = settings.userAgent();
        this.managedBy = adminId;
    }
}
