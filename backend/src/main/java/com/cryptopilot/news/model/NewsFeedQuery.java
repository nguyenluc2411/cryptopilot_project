package com.cryptopilot.news.model;

import com.cryptopilot.news.model.enums.SentimentLabel;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The articles one feed request asks for (UC-34).
 *
 * @param since only articles published at or after this instant
 * @param coinId only articles tagged with this coin, or {@code null} for all
 * @param sentiment only articles with this sentiment, or {@code null} for all, unsummarized ones included
 */
public record NewsFeedQuery(Instant since, UUID coinId, SentimentLabel sentiment) {

    public NewsFeedQuery {
        Objects.requireNonNull(since, "since must not be null");
    }
}
