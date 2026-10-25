package com.cryptopilot.news.model.enums;

/**
 * The tone the AI Service reads in an article (NSF-11), stored by name in {@code news_article.sentiment_label}
 * ({@code ck_news_article_sentiment}); {@code null} until the article is summarized.
 *
 * <p>Rule: NSF-11; UC-34 (the feed filters by it).
 */
public enum SentimentLabel {
    BULLISH,
    NEUTRAL,
    BEARISH
}
