package com.cryptopilot.news.dto.response;

import com.cryptopilot.news.model.enums.NewsArticleStatus;
import com.cryptopilot.news.model.enums.SentimentLabel;
import java.time.Instant;
import java.util.UUID;

/**
 * An article on the news page of the administration console (T-080b): the collected and the AI text side by side, the
 * status, and what NSF-11 last did with it.
 *
 * <p>Rule: NSF-11; BR-57; D-127, D-128.
 *
 * @param title the headline as collected
 * @param aiTitle the translated headline, or {@code null}
 * @param aiSummary the AI summary, or {@code null}
 * @param summaryAttempts how many times NSF-11 asked the model for it
 * @param summaryError why the latest attempt kept no summary, or {@code null}
 * @param hiddenAt when it was hidden from the feed, or {@code null} while it is shown
 */
public record AdminNewsArticleResponse(
        UUID articleId,
        UUID sourceId,
        String source,
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
        Instant hiddenAt) {}
