package com.cryptopilot.news.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientException;
import com.cryptopilot.news.client.RssFeed;
import com.cryptopilot.news.client.RssItem;
import com.cryptopilot.news.config.NewsCrawlProperties;
import com.cryptopilot.news.entity.NewsCrawlLog;
import com.cryptopilot.news.entity.NewsSource;
import com.cryptopilot.news.event.NewsSourceFailing;
import com.cryptopilot.news.model.CrawlRun;
import com.cryptopilot.news.model.NewsSourceSettings;
import com.cryptopilot.news.model.enums.CrawlStatus;
import com.cryptopilot.news.repository.NewsArticleRepository;
import com.cryptopilot.news.repository.NewsCrawlLogRepository;
import com.cryptopilot.news.repository.NewsSourceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NSF-10 without a database: what is stored and logged for each source, the coins it is tagged with, and the notice
 * to administrators sent once when a run of failures reaches the threshold.
 *
 * <p>Rule: NSF-10; BR-48, BR-49.
 */
class CrawlServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final UUID BTC = UUID.fromString("019b76da-a800-7c01-8000-000000000001");
    private static final UUID USDT = UUID.fromString("019b76da-a800-7c01-8000-000000000002");

    private final NewsSourceRepository sources = mock(NewsSourceRepository.class);
    private final NewsCrawlLogRepository crawlLogs = mock(NewsCrawlLogRepository.class);
    private final NewsArticleRepository articles = mock(NewsArticleRepository.class);
    private final RssClient rss = mock(RssClient.class);
    private final MarketApi market = mock(MarketApi.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private CrawlServiceImpl service;

    @BeforeEach
    void setUp() {
        NewsCrawlProperties properties = new NewsCrawlProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                Duration.ofHours(48),
                5,
                Duration.ofMinutes(1),
                Map.of("btc", List.of("Bitcoin")));
        service = new CrawlServiceImpl(
                sources,
                crawlLogs,
                articles,
                rss,
                market,
                properties,
                events,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(market.listedCoins())
                .thenReturn(List.of(new CoinListing(BTC, "BTC", "BTC"), new CoinListing(USDT, "USDT", "Tether")));
        when(sources.markCrawled(any(), any())).thenReturn(1);
        when(articles.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(crawlLogs.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void BR49_nothingDue_readsNothing() {
        when(sources.findDue(NOW)).thenReturn(List.of());

        assertThat(service.crawlDue()).isEqualTo(CrawlRun.NOTHING_DUE);
        verifyNoInteractions(rss, market);
    }

    @Test
    void NSF10_aFeed_isStoredWithItsCoins_andLoggedWithItsCounts() {
        NewsSource source = source(null);
        when(sources.findDue(NOW)).thenReturn(List.of(source));
        when(rss.read(source.getFeedUrl(), null))
                .thenReturn(feed(
                        item("Bitcoin holds $81K", "https://e.com/1", "Tether minted more USDT."),
                        item("Second story", "https://e.com/2", null)));

        CrawlRun run = service.crawlDue();

        assertThat(run).isEqualTo(new CrawlRun(1, 0, 2));
        verify(sources).markCrawled(source.getId(), NOW);
        ArgumentCaptor<UUID> article = ArgumentCaptor.forClass(UUID.class);
        verify(articles)
                .insert(
                        article.capture(),
                        eq(source.getId()),
                        eq("Bitcoin holds $81K"),
                        eq("https://e.com/1"),
                        any(),
                        any(),
                        eq("Tether minted more USDT."),
                        isNull(),
                        eq(NOW.minusSeconds(60)),
                        eq(NOW));
        verify(articles).tagCoin(article.getValue(), BTC, true);
        verify(articles).tagCoin(article.getValue(), USDT, false);
        NewsCrawlLog entry = logged();
        assertThat(entry.getStatus()).isEqualTo(CrawlStatus.SUCCESS);
        assertThat(entry.getArticlesFound()).isEqualTo(2);
        assertThat(entry.getArticlesInserted()).isEqualTo(2);
        verifyNoInteractions(events);
    }

    @Test
    void BR48_aStoryAlreadyTold_orAnAddressAlreadyStored_isNotCountedAsInserted() {
        NewsSource source = source(null);
        when(sources.findDue(NOW)).thenReturn(List.of(source));
        when(rss.read(any(), any()))
                .thenReturn(feed(
                        item("Told before", "https://e.com/1", null),
                        item("Stored before", "https://e.com/2", null),
                        item("New", "https://e.com/3", null)));
        when(articles.existsTitleBetween(any(), any(), any())).thenReturn(true, false, false);
        when(articles.insert(any(), any(), eq("Stored before"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThat(service.crawlDue().inserted()).isEqualTo(1);
        Instant published = NOW.minusSeconds(60);
        verify(articles, times(3))
                .existsTitleBetween(
                        any(), eq(published.minus(Duration.ofHours(48))), eq(published.plus(Duration.ofHours(48))));
        verify(articles, times(2)).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(logged().getArticlesInserted()).isEqualTo(1);
    }

    @Test
    void NSF10_itemsOutsideTheCategoryFilter_areSkipped_andUnusableItemsMakeTheCrawlPartial() {
        NewsSource source = source("markets");
        when(sources.findDue(NOW)).thenReturn(List.of(source));
        when(rss.read(any(), any()))
                .thenReturn(feed(
                        new RssItem("Kept", "https://e.com/1", null, null, List.of("Markets")),
                        new RssItem("Other desk", "https://e.com/2", null, null, List.of("Policy")),
                        new RssItem("No link", null, null, null, List.of("markets"))));

        service.crawlDue();

        verify(articles).insert(any(), any(), eq("Kept"), any(), any(), any(), any(), any(), any(), any());
        verify(articles, times(1)).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        NewsCrawlLog entry = logged();
        assertThat(entry.getStatus()).isEqualTo(CrawlStatus.PARTIAL);
        assertThat(entry.getArticlesFound()).isEqualTo(3);
        assertThat(entry.getErrorMessage()).isEqualTo("1 of 3 items had no headline or no web address");
    }

    @Test
    void NSF10_aSourceDeletedWhileItWasRead_writesNothing() {
        NewsSource source = source(null);
        when(sources.findDue(NOW)).thenReturn(List.of(source));
        when(rss.read(any(), any())).thenReturn(feed(item("A", "https://e.com/1", null)));
        when(sources.markCrawled(any(), any())).thenReturn(0);

        assertThat(service.crawlDue()).isEqualTo(new CrawlRun(1, 0, 0));
        verify(articles, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(crawlLogs, never()).save(any());
    }

    @Test
    void NSF10_anUnreadableFeed_isLoggedFailed_andWaitsOneInterval() {
        NewsSource source = source(null);
        when(sources.findDue(NOW)).thenReturn(List.of(source));
        when(rss.read(any(), any()))
                .thenThrow(new RssClientException(RssClientException.Reason.HTTP_STATUS, "answered HTTP 503"));
        when(crawlLogs.findLatestStatuses(source.getId(), 6)).thenReturn(List.of("FAILED", "SUCCESS"));

        assertThat(service.crawlDue()).isEqualTo(new CrawlRun(1, 1, 0));
        verify(sources).markCrawled(source.getId(), NOW);
        NewsCrawlLog entry = logged();
        assertThat(entry.getStatus()).isEqualTo(CrawlStatus.FAILED);
        assertThat(entry.getErrorMessage()).isEqualTo("HTTP_STATUS: answered HTTP 503");
        verifyNoInteractions(events, articles);
    }

    @Test
    void NSF10_theFifthFailureInARow_tellsTheAdministrators_once() {
        NewsSource source = source(null);
        when(sources.findDue(NOW)).thenReturn(List.of(source));
        when(rss.read(any(), any()))
                .thenThrow(new RssClientException(RssClientException.Reason.UNREACHABLE, "did not answer"));
        when(crawlLogs.findLatestStatuses(source.getId(), 6))
                .thenReturn(Collections.nCopies(4, "FAILED"))
                .thenReturn(List.of("FAILED", "FAILED", "FAILED", "FAILED", "FAILED", "SUCCESS"))
                .thenReturn(Collections.nCopies(6, "FAILED"));

        service.crawlDue();
        verifyNoInteractions(events);
        service.crawlDue();
        ArgumentCaptor<NewsSourceFailing> failing = ArgumentCaptor.forClass(NewsSourceFailing.class);
        verify(events).publishEvent(failing.capture());
        service.crawlDue();

        verify(events, times(1)).publishEvent(any(NewsSourceFailing.class));
        assertThat(failing.getValue().sourceId()).isEqualTo(source.getId());
        assertThat(failing.getValue().sourceName()).isEqualTo("Example");
        assertThat(failing.getValue().consecutiveFailures()).isEqualTo(5);
        assertThat(failing.getValue().error()).isEqualTo("UNREACHABLE: did not answer");
        assertThat(failing.getValue().failedAt()).isEqualTo(NOW);
    }

    @Test
    void NSF10_aDefectInOneSource_doesNotStopTheOthers() {
        NewsSource broken = source(null);
        NewsSource fine = NewsSource.create(
                new NewsSourceSettings("Fine", "https://fine.example.com/feed", 15, true, null, null, null, null),
                null);
        when(sources.findDue(NOW)).thenReturn(List.of(broken, fine));
        when(rss.read(eq(broken.getFeedUrl()), any())).thenThrow(new IllegalStateException("a bug"));
        when(rss.read(eq(fine.getFeedUrl()), any())).thenReturn(feed(item("A", "https://fine.example.com/a", null)));

        assertThat(service.crawlDue()).isEqualTo(new CrawlRun(2, 1, 1));
        verify(sources, never()).markCrawled(eq(broken.getId()), any());
    }

    @Test
    void NSF10_aCoinNamedAfterItsSymbol_isTaggedByItsConfiguredName() {
        NewsSource source = source(null);
        when(sources.findDue(NOW)).thenReturn(List.of(source));
        when(rss.read(any(), any())).thenReturn(feed(item("bitcoin slides", "https://e.com/1", null)));

        service.crawlDue();

        verify(articles).tagCoin(any(), eq(BTC), anyBoolean());
        verify(articles, times(1)).tagCoin(any(), any(), anyBoolean());
    }

    @Test
    void T080b_crawlNow_readsTheSourceEvenSwitchedOff_andAnswersItsLogEntry() {
        NewsSource off = NewsSource.create(
                new NewsSourceSettings("Off", "https://off.com/feed", 30, false, null, null, null, null), null);
        off.markCrawled(NOW.minus(Duration.ofMinutes(2)));
        when(sources.findById(off.getId())).thenReturn(Optional.of(off));
        when(rss.read(off.getFeedUrl(), null)).thenReturn(feed(item("Bitcoin holds", "https://off.com/1", null)));
        NewsCrawlLog latest = NewsCrawlLog.record(off.getId(), NOW, NOW, CrawlStatus.SUCCESS, 1, 1, null);
        when(crawlLogs.findLatestOf(List.of(off.getId()))).thenReturn(List.of(latest));

        assertThat(service.crawlNow(off.getId())).isSameAs(latest);

        verify(sources).markCrawled(off.getId(), NOW);
        assertThat(logged().getArticlesInserted()).isEqualTo(1);
    }

    @Test
    void T080b_crawlNow_withinTheCooldown_isRefusedWithTheSecondsLeft_andReadsNothing() {
        NewsSource source = source(null);
        source.markCrawled(NOW.minusSeconds(45));
        when(sources.findById(source.getId())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.crawlNow(source.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.NEWS_SOURCE_CRAWLED_RECENTLY);
                    assertThat(e.messageArgs()).containsExactly("15");
                });
        verifyNoInteractions(rss);
    }

    @Test
    void T080b_crawlNow_ofAnUnknownSource_isNotFound() {
        UUID unknown = UUID.randomUUID();
        when(sources.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crawlNow(unknown)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(rss);
    }

    private NewsCrawlLog logged() {
        ArgumentCaptor<NewsCrawlLog> entry = ArgumentCaptor.forClass(NewsCrawlLog.class);
        verify(crawlLogs).save(entry.capture());
        return entry.getValue();
    }

    private static NewsSource source(String categoryFilter) {
        return NewsSource.create(
                new NewsSourceSettings("Example", "https://e.com/feed", 15, true, categoryFilter, null, null, null),
                null);
    }

    private static RssFeed feed(RssItem... items) {
        return new RssFeed("Feed", List.of(items));
    }

    private static RssItem item(String title, String link, String description) {
        return new RssItem(title, link, NOW.minusSeconds(60), description, List.of());
    }
}
