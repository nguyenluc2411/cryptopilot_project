package com.cryptopilot.news.dto.response;

import com.cryptopilot.news.model.enums.CrawlStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a source's crawl log on SCR-41: time, status, articles found and inserted, and the error (UC-49).
 *
 * <p>Rule: UC-49; NSF-10.
 */
public record NewsCrawlLogResponse(
        UUID crawlLogId,
        Instant startedAt,
        Instant finishedAt,
        CrawlStatus status,
        Integer articlesFound,
        Integer articlesInserted,
        String errorMessage) {}
