package com.cryptopilot.news.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.news.model.enums.SentimentLabel;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * A collected article: what BR-48 allows to keep of it — the title, the original address, the publish time, an excerpt
 * and, once NSF-11 has run, the translated headline, the AI summary and its sentiment — and the two hashes duplicates are found by. The full
 * text is never stored. Written by {@link com.cryptopilot.news.repository.NewsArticleRepository#insert} with a single
 * statement that leaves an address already stored alone, so this entity is read only here.
 *
 * <p>Rule: BR-48; NSF-10, NSF-11; UC-34.
 */
@Getter
@Entity
@Table(name = "news_article")
@AttributeOverride(name = "id", column = @Column(name = "article_id", nullable = false, updatable = false))
public class NewsArticle extends BaseEntity {

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "title", nullable = false, length = 500, updatable = false)
    private String title;

    @Column(name = "original_url", nullable = false, length = 2048, updatable = false)
    private String originalUrl;

    @Column(name = "url_hash", nullable = false, length = 64, updatable = false)
    private String urlHash;

    @Column(name = "title_hash", nullable = false, length = 64, updatable = false)
    private String titleHash;

    @Column(name = "excerpt", updatable = false)
    private String excerpt;

    /** The headline in the summary's language (D-127); {@code null} until NSF-11 has run, when {@link #title} is shown. */
    @Column(name = "ai_title", length = 500)
    private String aiTitle;

    @Column(name = "ai_summary")
    private String aiSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "sentiment_label", length = 32)
    private SentimentLabel sentimentLabel;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "collected_at", nullable = false, updatable = false)
    private Instant collectedAt;

    /** For JPA only. */
    protected NewsArticle() {}
}
