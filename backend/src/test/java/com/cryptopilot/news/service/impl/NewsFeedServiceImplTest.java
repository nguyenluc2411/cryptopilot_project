package com.cryptopilot.news.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.news.dto.response.NewsArticleResponse;
import com.cryptopilot.news.dto.response.NewsCoinResponse;
import com.cryptopilot.news.dto.response.NewsSentimentSummaryResponse;
import com.cryptopilot.news.model.CoinTag;
import com.cryptopilot.news.model.FeedArticle;
import com.cryptopilot.news.model.FeedArticlePage;
import com.cryptopilot.news.model.NewsFeedQuery;
import com.cryptopilot.news.model.enums.SentimentLabel;
import com.cryptopilot.news.repository.NewsFeedRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * The feed without a database: what is asked of the repository, which text an item shows, and that a page costs the
 * same few reads whatever its size.
 *
 * <p>Rule: UC-34; SRS 3.8.1; CR-04; D-130, D-127.
 */
class NewsFeedServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final UUID BTC = UUID.randomUUID();
    private static final UUID ETH = UUID.randomUUID();

    private final NewsFeedRepository feeds = mock(NewsFeedRepository.class);
    private final MarketApi market = mock(MarketApi.class);
    private final NewsFeedServiceImpl service =
            new NewsFeedServiceImpl(feeds, market, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void D118_anItemShowsTheAiText_orTheCollectedTextUntilThereIsOne() {
        FeedArticle summarized =
                article("ETF outflows", "ETF bị rút ròng", "Excerpt.", "Tóm tắt.", SentimentLabel.BEARISH);
        FeedArticle beforeTitles = article("Old one", null, "Old excerpt.", "Tóm tắt cũ.", SentimentLabel.NEUTRAL);
        FeedArticle collected = article("Fresh", null, "Fresh excerpt.", null, null);
        when(feeds.feed(any(), any())).thenReturn(new FeedArticlePage(List.of(summarized, beforeTitles, collected), 3));
        when(feeds.coinTags(any()))
                .thenReturn(Map.of(summarized.articleId(), List.of(new CoinTag(BTC, true), new CoinTag(ETH, false))));
        when(market.coins(any())).thenReturn(List.of(coin(BTC, "BTC"), coin(ETH, "ETH")));

        List<NewsArticleResponse> items =
                service.feed(null, null, null, null, null).items();

        assertThat(items.get(0))
                .extracting(
                        NewsArticleResponse::title,
                        NewsArticleResponse::originalTitle,
                        NewsArticleResponse::summary,
                        NewsArticleResponse::aiSummarized,
                        NewsArticleResponse::sentiment)
                .containsExactly("ETF bị rút ròng", "ETF outflows", "Tóm tắt.", true, SentimentLabel.BEARISH);
        assertThat(items.get(0).coins())
                .containsExactly(new NewsCoinResponse("BTC", true), new NewsCoinResponse("ETH", false));
        assertThat(items.get(1))
                .extracting(NewsArticleResponse::title, NewsArticleResponse::summary, NewsArticleResponse::aiSummarized)
                .containsExactly("Old one", "Tóm tắt cũ.", true);
        assertThat(items.get(2))
                .extracting(
                        NewsArticleResponse::title,
                        NewsArticleResponse::summary,
                        NewsArticleResponse::aiSummarized,
                        NewsArticleResponse::sentiment)
                .containsExactly("Fresh", "Fresh excerpt.", false, null);
        assertThat(items.get(2).coins()).isEmpty();
    }

    @Test
    void UC34_theFiltersReachTheRepository_sevenDaysWhenNoPeriodIsNamed() {
        when(feeds.feed(any(), any())).thenReturn(new FeedArticlePage(List.of(), 0));
        when(market.coinsBySymbol(List.of("BTC"))).thenReturn(List.of(coin(BTC, "BTC")));

        service.feed(" btc ", "Bullish", null, 2, 5);
        service.feed(null, null, "24H", null, null);

        ArgumentCaptor<NewsFeedQuery> queries = ArgumentCaptor.forClass(NewsFeedQuery.class);
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(feeds, times(2)).feed(queries.capture(), pages.capture());
        assertThat(queries.getAllValues())
                .containsExactly(
                        new NewsFeedQuery(NOW.minus(Duration.ofDays(7)), BTC, SentimentLabel.BULLISH),
                        new NewsFeedQuery(NOW.minus(Duration.ofHours(24)), null, null));
        assertThat(pages.getAllValues().getFirst().getPageNumber()).isEqualTo(1);
        assertThat(pages.getAllValues().getFirst().getPageSize()).isEqualTo(5);
    }

    @Test
    void UC34_aCoinNoCoinHas_isAnEmptyPage_withoutReadingTheFeed() {
        when(market.coinsBySymbol(any())).thenReturn(List.of());

        PageResponse<NewsArticleResponse> page = service.feed("NOPE", null, "30d", 3, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(10);
        verifyNoInteractions(feeds);
    }

    @Test
    void UC34_anUnknownPeriodOrSentiment_isRefusedUnderItsName() {
        assertThatThrownBy(() -> service.feed(null, null, "1y", null, null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getMessage())
                        .contains("24h, 7d or 30d"));
        assertThatThrownBy(() -> service.feed(null, "MIXED", null, null, null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getMessage())
                        .contains("BULLISH, NEUTRAL or BEARISH"));
        verifyNoInteractions(feeds, market);
    }

    @Test
    void CR04_aPageWithoutCoinTags_asksTheMarketNothing() {
        when(feeds.feed(any(), any())).thenReturn(new FeedArticlePage(List.of(article("A", null, "x", null, null)), 1));
        when(feeds.coinTags(any())).thenReturn(Map.of());

        assertThat(service.feed("", null, " ", null, null).total()).isEqualTo(1);
        verify(market, never()).coins(any());
        verify(market, never()).coinsBySymbol(any());
    }

    @Test
    void UC34_theSummaryBar_countsTheLast24Hours() {
        Map<SentimentLabel, Long> counts = new EnumMap<>(SentimentLabel.class);
        counts.put(SentimentLabel.BULLISH, 4L);
        counts.put(SentimentLabel.NEUTRAL, 0L);
        counts.put(SentimentLabel.BEARISH, 2L);
        when(feeds.sentimentCounts(NOW.minus(Duration.ofHours(24)))).thenReturn(counts);

        assertThat(service.sentimentSummary())
                .isEqualTo(new NewsSentimentSummaryResponse(NOW.minus(Duration.ofHours(24)), 4, 0, 2));
    }

    private static FeedArticle article(
            String title, String aiTitle, String excerpt, String aiSummary, SentimentLabel sentiment) {
        return new FeedArticle(
                UUID.randomUUID(),
                title,
                aiTitle,
                "Desk",
                "https://e.com/" + title.hashCode(),
                excerpt,
                aiSummary,
                sentiment,
                NOW.minusSeconds(60),
                null,
                null);
    }

    private static CoinListing coin(UUID id, String symbol) {
        return new CoinListing(id, symbol, symbol);
    }
}
