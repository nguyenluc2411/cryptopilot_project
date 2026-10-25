package com.cryptopilot.news.model;

import com.cryptopilot.news.model.enums.SentimentLabel;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An article as the feed reads it, with the name of the source it came from (UC-34).
 *
 * @param articleId the article
 * @param title the headline as collected
 * @param aiTitle the translated headline (D-127), or {@code null} until NSF-11 has run
 * @param sourceName the outlet
 * @param originalUrl where the article is read
 * @param excerpt the feed's excerpt, or {@code null}
 * @param aiSummary the AI summary (NSF-11), or {@code null} until NSF-11 has run
 * @param sentiment the tone of the article, or {@code null} until NSF-11 has run
 * @param publishedAt when the outlet published it
 * @param imageUrl the address of the outlet's cover image (D-129), or {@code null}
 * @param aiBody the article written again (D-129), or {@code null}; read for the article's page only, never for a page
 *     of the feed
 */
public record FeedArticle(
        UUID articleId,
        String title,
        String aiTitle,
        String sourceName,
        String originalUrl,
        String excerpt,
        String aiSummary,
        SentimentLabel sentiment,
        Instant publishedAt,
        String imageUrl,
        String aiBody) {

    public FeedArticle {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }
}
