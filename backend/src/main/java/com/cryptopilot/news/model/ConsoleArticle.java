package com.cryptopilot.news.model;

import com.cryptopilot.news.model.enums.NewsArticleStatus;
import com.cryptopilot.news.model.enums.SentimentLabel;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An article as the administration console reads it (T-080b): what the feed shows, and what NSF-11 and the moderators
 * did with it.
 *
 * @param status worked out from the row, see {@link NewsArticleStatus}
 * @param summaryAttempts how many times NSF-11 asked the model for it
 * @param summaryError why the latest attempt kept no summary, or {@code null}
 * @param hiddenAt when it was hidden, or {@code null} while it is shown
 */
public record ConsoleArticle(
        UUID articleId,
        UUID sourceId,
        String sourceName,
        String title,
        String aiTitle,
        String originalUrl,
        String excerpt,
        String aiSummary,
        SentimentLabel sentiment,
        Instant publishedAt,
        Instant collectedAt,
        NewsArticleStatus status,
        int summaryAttempts,
        String summaryError,
        Instant summaryAttemptedAt,
        Instant hiddenAt) {

    public ConsoleArticle {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
