package com.cryptopilot.news.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.news.dto.response.NewsArticleResponse;
import com.cryptopilot.news.dto.response.NewsCoinResponse;
import com.cryptopilot.news.dto.response.NewsSentimentSummaryResponse;
import com.cryptopilot.news.model.CoinTag;
import com.cryptopilot.news.model.FeedArticle;
import com.cryptopilot.news.model.FeedArticlePage;
import com.cryptopilot.news.model.NewsFeedQuery;
import com.cryptopilot.news.model.enums.NewsPeriod;
import com.cryptopilot.news.model.enums.SentimentLabel;
import com.cryptopilot.news.repository.NewsFeedRepository;
import com.cryptopilot.news.service.NewsFeedService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The news feed (UC-34). Every Trader reads the same items (D-126): the translated headline and the AI summary once
 * NSF-11 has run (D-127), the collected headline and the feed's excerpt until then, and the sentiment. A page costs a
 * fixed number of statements: the page, its count, its coin tags and the symbols of those coins.
 *
 * <p>A coin symbol no coin has matches nothing rather than failing, as a post feed does: a link to the news of a coin
 * that is not listed shows an empty feed.
 *
 * <p>Rule: UC-34; SRS 3.8.1; CR-04; D-126, D-127.
 */
@Service
@RequiredArgsConstructor
public class NewsFeedServiceImpl implements NewsFeedService {

    /** The window of the sentiment summary bar (SRS 3.8.1). */
    static final Duration SUMMARY_WINDOW = Duration.ofHours(24);

    private final NewsFeedRepository feeds;
    private final MarketApi market;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NewsArticleResponse> feed(
            String coin, String sentiment, String range, Integer page, Integer pageSize) {
        PageRequest request = Paging.of(page, pageSize);
        NewsPeriod period = period(range);
        SentimentLabel label = sentiment(sentiment);
        UUID coinId = null;
        if (coin != null && !coin.isBlank()) {
            Optional<UUID> stored = coinId(coin);
            if (stored.isEmpty()) {
                return new PageResponse<>(List.of(), request.getPageNumber() + 1, request.getPageSize(), 0);
            }
            coinId = stored.get();
        }
        Instant since = clock.instant().minus(period.length());
        FeedArticlePage found = feeds.feed(new NewsFeedQuery(since, coinId, label), request);
        List<UUID> ids = found.articles().stream().map(FeedArticle::articleId).toList();
        Map<UUID, List<CoinTag>> tags = feeds.coinTags(ids);
        Map<UUID, String> symbols = symbols(tags);
        List<NewsArticleResponse> items = found.articles().stream()
                .map(article -> response(article, tags.getOrDefault(article.articleId(), List.of()), symbols))
                .toList();
        return new PageResponse<>(items, request.getPageNumber() + 1, request.getPageSize(), found.total());
    }

    @Override
    @Transactional(readOnly = true)
    public NewsSentimentSummaryResponse sentimentSummary() {
        Instant since = clock.instant().minus(SUMMARY_WINDOW);
        Map<SentimentLabel, Long> counts = feeds.sentimentCounts(since);
        return new NewsSentimentSummaryResponse(
                since,
                counts.get(SentimentLabel.BULLISH),
                counts.get(SentimentLabel.NEUTRAL),
                counts.get(SentimentLabel.BEARISH));
    }

    private static NewsPeriod period(String range) {
        if (range == null || range.isBlank()) {
            return NewsPeriod.DEFAULT;
        }
        return NewsPeriod.ofCode(range)
                .orElseThrow(() -> new FieldValidationException(
                        "range is 24h, 7d or 30d, was " + range, Map.of("range", "MSG15")));
    }

    private static SentimentLabel sentiment(String sentiment) {
        if (sentiment == null || sentiment.isBlank()) {
            return null;
        }
        String name = sentiment.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(SentimentLabel.values())
                .filter(label -> label.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new FieldValidationException(
                        "sentiment is BULLISH, NEUTRAL or BEARISH, was " + sentiment, Map.of("sentiment", "MSG15")));
    }

    private Optional<UUID> coinId(String symbol) {
        return market.coinsBySymbol(List.of(symbol.strip().toUpperCase(Locale.ROOT))).stream()
                .map(CoinListing::coinId)
                .findFirst();
    }

    private Map<UUID, String> symbols(Map<UUID, List<CoinTag>> tags) {
        List<UUID> coinIds = tags.values().stream()
                .flatMap(List::stream)
                .map(CoinTag::coinId)
                .distinct()
                .toList();
        if (coinIds.isEmpty()) {
            return Map.of();
        }
        return market.coins(coinIds).stream()
                .collect(Collectors.toMap(CoinListing::coinId, CoinListing::symbol, (first, second) -> first));
    }

    private static NewsArticleResponse response(FeedArticle article, List<CoinTag> tags, Map<UUID, String> symbols) {
        boolean summarized = article.aiSummary() != null;
        List<NewsCoinResponse> coins = tags.stream()
                .filter(tag -> symbols.containsKey(tag.coinId()))
                .map(tag -> new NewsCoinResponse(symbols.get(tag.coinId()), tag.primary()))
                .toList();
        return new NewsArticleResponse(
                article.articleId(),
                Optional.ofNullable(article.aiTitle()).orElse(article.title()),
                article.title(),
                article.sourceName(),
                article.originalUrl(),
                article.publishedAt(),
                summarized ? article.aiSummary() : article.excerpt(),
                summarized,
                article.sentiment(),
                coins);
    }
}
