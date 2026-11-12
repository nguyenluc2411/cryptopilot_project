package com.cryptopilot.news.service.impl;

import com.cryptopilot.ai.AssistantArticle;
import com.cryptopilot.ai.AssistantNews;
import com.cryptopilot.ai.AssistantNewsSource;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.news.config.NewsAssistantProperties;
import com.cryptopilot.news.model.FeedArticle;
import com.cryptopilot.news.repository.NewsFeedRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The news the AI Assistant reads (T-074): the latest shown articles about the base coin of the pair asked about, the
 * ones whose headline names it first, and the latest articles that name no coin, which are about the market in general.
 * The headline is the Vietnamese one NSF-11 wrote when there is one, and the summary is the AI summary, else the
 * feed's excerpt, cut to a few sentences so the context stays small.
 *
 * <p>Rule: BR-51 (an article carries nothing about the Trader), NSF-10, NSF-11; D-141 (the same news for every plan).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class NewsForAssistant implements AssistantNewsSource {

    /** The longest headline sent; an outlet's headline rarely needs more. */
    static final int MAX_HEADLINE_CHARS = 200;

    /**
     * The longest link sent. A longer one (tracking parameters) is left out with its article, so that eight articles
     * always fit in the context the AI Service accepts (NSF-16, 20,000 characters with the market and the plan).
     */
    static final int MAX_URL_CHARS = 500;

    private final NewsFeedRepository feed;
    private final MarketApi market;
    private final NewsAssistantProperties properties;
    private final Clock clock;

    @Override
    public AssistantNews recentNews(UUID pairId) {
        Objects.requireNonNull(pairId, "pairId");
        Optional<PairCoins> coins = market.pairCoins(pairId);
        if (coins.isEmpty()) {
            return AssistantNews.NONE;
        }
        Instant now = clock.instant();
        List<AssistantArticle> aboutCoin = feed
                .latestAboutCoin(
                        coins.get().base().coinId(), now.minus(properties.coinWindow()), properties.coinArticles())
                .stream()
                .filter(NewsForAssistant::usable)
                .map(this::article)
                .toList();
        List<AssistantArticle> aboutMarket =
                feed.latestAboutMarket(now.minus(properties.marketWindow()), properties.marketArticles()).stream()
                        .filter(NewsForAssistant::usable)
                        .map(this::article)
                        .toList();
        return new AssistantNews(
                coins.get().base().symbol(), feed.lastCrawledAt().orElse(null), aboutCoin, aboutMarket);
    }

    private AssistantArticle article(FeedArticle article) {
        String headline = headline(article);
        String summary =
                article.aiSummary() != null && !article.aiSummary().isBlank() ? article.aiSummary() : article.excerpt();
        return new AssistantArticle(
                cut(headline, MAX_HEADLINE_CHARS),
                article.sourceName(),
                article.publishedAt(),
                cut(summary, properties.summaryChars()),
                article.sentiment() == null ? null : article.sentiment().name(),
                article.originalUrl());
    }

    /**
     * Whether the article can be sent: its link fits, and it has a headline (the crawl stores none without one; this
     * keeps one bad row from failing the question).
     */
    private static boolean usable(FeedArticle article) {
        return article.originalUrl() != null
                && article.originalUrl().length() <= MAX_URL_CHARS
                && cut(headline(article), MAX_HEADLINE_CHARS) != null;
    }

    private static String headline(FeedArticle article) {
        return article.aiTitle() != null && !article.aiTitle().isBlank() ? article.aiTitle() : article.title();
    }

    /** {@code text} at most {@code max} characters, cut at the last space before it and marked with an ellipsis. */
    static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() <= max) {
            return stripped;
        }
        int end = stripped.lastIndexOf(' ', max - 1);
        return stripped.substring(0, end > 0 ? end : max - 1).stripTrailing() + "…";
    }
}
