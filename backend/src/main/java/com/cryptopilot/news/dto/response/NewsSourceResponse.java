package com.cryptopilot.news.dto.response;

import com.cryptopilot.news.model.enums.CrawlStatus;
import com.cryptopilot.news.model.enums.SourceType;
import java.time.Instant;
import java.util.UUID;

/**
 * A news source as SCR-41 shows it: its settings, when it was last read and how that ended, and when it is read next.
 *
 * <p>Rule: UC-49; BR-49; NSF-10.
 *
 * @param lastCrawledAt when the crawl last read the feed, or {@code null} when it never has
 * @param lastCrawl the latest crawl log entry, or {@code null} when there is none
 * @param nextCrawlAt when the source is next due, one interval after it was last read (BR-49); {@code null} when it is
 *     switched off, or when it was never read and the next run of the crawl reads it
 */
public record NewsSourceResponse(
        UUID sourceId,
        String sourceName,
        String feedUrl,
        SourceType sourceType,
        int crawlIntervalMinutes,
        boolean active,
        String categoryFilter,
        String bodySelector,
        String excludedSelectors,
        String userAgent,
        Instant lastCrawledAt,
        LastCrawl lastCrawl,
        Instant nextCrawlAt,
        Instant createdAt,
        Instant updatedAt) {

    /** The latest crawl of the source, summed up for the list. */
    public record LastCrawl(Instant startedAt, CrawlStatus status, Integer articlesFound, Integer articlesInserted) {}
}
