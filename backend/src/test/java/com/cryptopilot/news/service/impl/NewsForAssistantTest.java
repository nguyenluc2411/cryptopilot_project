package com.cryptopilot.news.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.ai.AssistantArticle;
import com.cryptopilot.ai.AssistantNews;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.news.config.NewsAssistantProperties;
import com.cryptopilot.news.model.FeedArticle;
import com.cryptopilot.news.model.enums.SentimentLabel;
import com.cryptopilot.news.repository.NewsFeedRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The news the AI Assistant reads, without a database: which coin and windows are asked for, which text of an article
 * is sent, and nothing when the pair is unknown.
 *
 * <p>Rule: BR-51, NSF-10, NSF-11; T-074.
 */
class NewsForAssistantTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final UUID PAIR = UUID.randomUUID();
    private static final UUID BTC = UUID.randomUUID();

    private final NewsFeedRepository feed = mock(NewsFeedRepository.class);
    private final MarketApi market = mock(MarketApi.class);
    private final NewsAssistantProperties properties =
            new NewsAssistantProperties(5, Duration.ofHours(72), 3, Duration.ofHours(24), 80);
    private final NewsForAssistant news =
            new NewsForAssistant(feed, market, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void T074_readsTheBaseCoinsArticlesAndTheMarkets_inTheirWindows_withTheTimeOfTheLastCrawl() {
        when(market.pairCoins(PAIR))
                .thenReturn(Optional.of(new PairCoins(
                        PAIR, new CoinListing(BTC, "BTC", "BTC"), new CoinListing(UUID.randomUUID(), "USDT", "USDT"))));
        FeedArticle translated = article(
                "Bitcoin ETFs see outflows",
                "ETF Bitcoin bị rút ròng",
                "Excerpt.",
                "Quỹ ETF Bitcoin bị rút ròng 635 triệu USD.",
                SentimentLabel.BEARISH);
        FeedArticle collected = article("Exchange hacked", null, "An exchange lost $6.3B.", null, null);
        when(feed.latestAboutCoin(BTC, NOW.minus(Duration.ofHours(72)), 5)).thenReturn(List.of(translated));
        when(feed.latestAboutMarket(NOW.minus(Duration.ofHours(24)), 3)).thenReturn(List.of(collected));
        Instant crawled = NOW.minus(Duration.ofMinutes(7));
        when(feed.lastCrawledAt()).thenReturn(Optional.of(crawled));

        AssistantNews read = news.recentNews(PAIR);

        assertThat(read.coin()).isEqualTo("BTC");
        assertThat(read.checkedUpTo()).isEqualTo(crawled);
        assertThat(read.coinArticles())
                .containsExactly(new AssistantArticle(
                        "ETF Bitcoin bị rút ròng",
                        "Desk",
                        translated.publishedAt(),
                        "Quỹ ETF Bitcoin bị rút ròng 635 triệu USD.",
                        "BEARISH",
                        translated.originalUrl()));
        assertThat(read.marketArticles())
                .containsExactly(new AssistantArticle(
                        "Exchange hacked",
                        "Desk",
                        collected.publishedAt(),
                        "An exchange lost $6.3B.",
                        null,
                        collected.originalUrl()));
    }

    @Test
    void T074_anUnknownPair_hasNoNews_andTheFeedIsNotRead() {
        when(market.pairCoins(PAIR)).thenReturn(Optional.empty());

        assertThat(news.recentNews(PAIR)).isEqualTo(AssistantNews.NONE);
        assertThat(AssistantNews.NONE.isEmpty()).isTrue();
        verifyNoInteractions(feed);
    }

    @Test
    void T074_noCrawlYet_leavesTheTimeEmpty() {
        when(market.pairCoins(PAIR))
                .thenReturn(Optional.of(new PairCoins(
                        PAIR, new CoinListing(BTC, "BTC", "BTC"), new CoinListing(UUID.randomUUID(), "USDT", "USDT"))));
        when(feed.latestAboutCoin(any(), any(), anyInt())).thenReturn(List.of());
        when(feed.latestAboutMarket(any(), anyInt())).thenReturn(List.of());
        when(feed.lastCrawledAt()).thenReturn(Optional.empty());

        AssistantNews read = news.recentNews(PAIR);

        assertThat(read.checkedUpTo()).isNull();
        assertThat(read.isEmpty()).isTrue();
        verify(feed).latestAboutCoin(BTC, NOW.minus(Duration.ofHours(72)), 5);
    }

    @Test
    void T074_aLongSummary_isCutAtAWord_andABlankOneIsDropped() {
        assertThat(NewsForAssistant.cut("one two three four", 12)).isEqualTo("one two…");
        assertThat(NewsForAssistant.cut("abcdefghijklmnop", 8)).isEqualTo("abcdefg…");
        assertThat(NewsForAssistant.cut("  short  ", 12)).isEqualTo("short");
        assertThat(NewsForAssistant.cut("   ", 12)).isNull();
        assertThat(NewsForAssistant.cut(null, 12)).isNull();
    }

    private static FeedArticle article(
            String title, String aiTitle, String excerpt, String aiSummary, SentimentLabel sentiment) {
        UUID id = UUID.randomUUID();
        return new FeedArticle(
                id,
                title,
                aiTitle,
                "Desk",
                "https://e.com/" + id,
                excerpt,
                aiSummary,
                sentiment,
                NOW.minus(Duration.ofHours(1)),
                null,
                null);
    }
}
