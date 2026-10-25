package com.cryptopilot.news.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientException;
import com.cryptopilot.news.client.RssFeed;
import com.cryptopilot.news.dto.request.NewsSourceRequest;
import com.cryptopilot.news.dto.response.NewsCrawlLogResponse;
import com.cryptopilot.news.dto.response.NewsSourceResponse;
import com.cryptopilot.news.entity.NewsCrawlLog;
import com.cryptopilot.news.entity.NewsSource;
import com.cryptopilot.news.model.NewsSourceSettings;
import com.cryptopilot.news.model.enums.CrawlStatus;
import com.cryptopilot.news.repository.NewsCrawlLogRepository;
import com.cryptopilot.news.repository.NewsSourceRepository;
import com.cryptopilot.news.service.CrawlService;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SCR-41 without a database: the request checked field by field, the feed read before anything is saved and outside
 * the transaction, duplicates refused before and inside it, and every change audited with what changed (BR-57).
 *
 * <p>Rule: UC-49; BR-49, BR-57; NSF-10.
 */
class NewsSourceServiceImplTest {

    private static final UUID ADMIN = UUID.fromString("019b76da-a800-7d01-8000-0000000000aa");
    private static final String FEED = "https://example.com/feed";
    private static final Instant READ_AT = Instant.parse("2026-10-25T09:00:00Z");

    private final NewsSourceRepository sources = mock(NewsSourceRepository.class);
    private final NewsCrawlLogRepository crawlLogs = mock(NewsCrawlLogRepository.class);
    private final RssClient rss = mock(RssClient.class);
    private final AdminApi admin = mock(AdminApi.class);
    private final CrawlService crawls = mock(CrawlService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private NewsSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NewsSourceServiceImpl(
                sources, crawlLogs, rss, admin, crawls, new TransactionTemplate(transactionManager));
        when(sources.save(any())).thenAnswer(call -> call.getArgument(0));
        when(rss.read(anyString(), any())).thenReturn(new RssFeed("Feed", List.of()));
    }

    @Test
    void T080b_crawlNow_isTheCrawlOfTheSource_answeredAsItsLogEntry() {
        UUID sourceId = UUID.randomUUID();
        Instant started = Instant.parse("2026-10-25T10:00:00Z");
        when(crawls.crawlNow(sourceId))
                .thenReturn(NewsCrawlLog.record(
                        sourceId, started, started.plusSeconds(3), CrawlStatus.PARTIAL, 12, 4, "1 of 12 items had no"));

        NewsCrawlLogResponse log = service.crawlNow(sourceId);

        assertThat(log.status()).isEqualTo(CrawlStatus.PARTIAL);
        assertThat(log.articlesFound()).isEqualTo(12);
        assertThat(log.articlesInserted()).isEqualTo(4);
        assertThat(log.startedAt()).isEqualTo(started);
        verifyNoInteractions(admin);
    }

    @Test
    void UC49_aNewSource_isReadThenSavedNormalizedAndAudited() {
        NewsSourceResponse created = service.create(
                ADMIN,
                new NewsSourceRequest(
                        "  Example Markets ",
                        "HTTPS://Example.com/feed?utm_source=console#x",
                        30,
                        true,
                        " https://example.com/markets ",
                        "div.body, main#content",
                        "figure, .ad",
                        " Agent/1.0 "));

        assertThat(created.sourceName()).isEqualTo("Example Markets");
        assertThat(created.feedUrl()).isEqualTo(FEED);
        assertThat(created.crawlIntervalMinutes()).isEqualTo(30);
        assertThat(created.categoryFilter()).isEqualTo("https://example.com/markets");
        assertThat(created.userAgent()).isEqualTo("Agent/1.0");
        assertThat(created.lastCrawledAt()).isNull();
        assertThat(created.lastCrawl()).isNull();
        assertThat(created.nextCrawlAt())
                .as("never read: the next run reads it")
                .isNull();

        InOrder order = inOrder(sources, rss, transactionManager);
        order.verify(sources).existsByFeedUrl(FEED);
        order.verify(rss).read(FEED, "Agent/1.0");
        order.verify(transactionManager).getTransaction(any());
        order.verify(sources).save(any());
        order.verify(sources).flush();
        AuditEntry entry = audited();
        assertThat(entry.actorId()).isEqualTo(ADMIN);
        assertThat(entry.action()).isEqualTo(AuditAction.CONFIGURATION_CREATED);
        assertThat(entry.entityType()).isEqualTo(AuditedEntity.NEWS_SOURCE);
        assertThat(entry.entityId()).isEqualTo(created.sourceId());
        assertThat(entry.oldValue()).isNull();
        assertThat(entry.newValue())
                .containsEntry("feedUrl", FEED)
                .containsEntry("crawlIntervalMinutes", 30)
                .containsEntry("bodySelector", "div.body, main#content");
    }

    @Test
    void UC49_blankOptionalValues_areStoredAsNone_andTheDefaultAgentIsUsed() {
        NewsSourceResponse created =
                service.create(ADMIN, new NewsSourceRequest("Example", FEED, 15, false, " ", "", "  ", ""));

        assertThat(created.categoryFilter()).isNull();
        assertThat(created.bodySelector()).isNull();
        assertThat(created.excludedSelectors()).isNull();
        assertThat(created.userAgent()).isNull();
        assertThat(created.active()).isFalse();
        verify(rss).read(FEED, null);
    }

    @Test
    void UC49_aFeedAlreadyRegistered_isRefusedBeforeItIsRead() {
        when(sources.existsByFeedUrl(FEED)).thenReturn(true);

        assertCode(() -> service.create(ADMIN, request(FEED)), ErrorCode.NEWS_SOURCE_ALREADY_REGISTERED);
        verifyNoInteractions(rss, admin);
        verify(sources, never()).save(any());
    }

    @Test
    void UC49_aFeedRegisteredWhileThisOneWasRead_isStillRefused() {
        when(sources.existsByFeedUrl(FEED)).thenReturn(false, true);

        assertCode(() -> service.create(ADMIN, request(FEED)), ErrorCode.NEWS_SOURCE_ALREADY_REGISTERED);
        verify(sources, never()).save(any());
        verifyNoInteractions(admin);
    }

    @Test
    void UC49_theUniqueKey_hasTheLastWord_andOtherIntegrityFailuresAreNotDisguised() {
        when(sources.save(any()))
                .thenThrow(integrity("duplicate key value violates unique constraint \"uq_news_source_feed_url\""))
                .thenThrow(integrity("insert or update violates foreign key constraint \"fk_news_source_managed_by\""));

        assertCode(() -> service.create(ADMIN, request(FEED)), ErrorCode.NEWS_SOURCE_ALREADY_REGISTERED);
        assertThatThrownBy(() -> service.create(ADMIN, request(FEED)))
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(admin);
    }

    @Test
    void UC49_aFeedThatCannotBeRead_isRefusedUnderFeedUrl_andNothingIsSaved() {
        when(rss.read(anyString(), any()))
                .thenThrow(new RssClientException(RssClientException.Reason.NOT_A_FEED, FEED + " is a web page"));

        assertThatThrownBy(() -> service.create(ADMIN, request(FEED)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.NEWS_FEED_UNREADABLE);
                    assertThat(e.errors()).containsExactly(Map.entry("feedUrl", "MSG15"));
                    assertThat(e.getMessage()).contains("NOT_A_FEED");
                });
        verify(sources, never()).save(any());
        verifyNoInteractions(transactionManager, admin);
    }

    @Test
    void UC49_anAddressThatIsNoWebAddress_andSelectorsThatDoNotParse_areNamedTogether() {
        NewsSourceRequest request = new NewsSourceRequest(
                "Example", "ftp://example.com/feed", 15, true, null, "div[", "div:nonsense", null);

        assertThatThrownBy(() -> service.create(ADMIN, request))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors())
                            .containsExactly(
                                    Map.entry("feedUrl", "MSG15"),
                                    Map.entry("bodySelector", "MSG15"),
                                    Map.entry("excludedSelectors", "MSG15"));
                });
        verifyNoInteractions(rss, admin);
    }

    @Test
    void UC49_anAddressLongerThanTheColumnOnceNormalized_isRefused() {
        String host = "a".repeat(988) + ".com";
        String address = "https://" + host;
        assertThat(address).hasSize(1000);

        assertThatThrownBy(() -> service.create(ADMIN, request(address)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.errors())
                        .containsOnlyKeys("feedUrl"));
    }

    @Test
    void UC49_anUpdateThatChangesNothing_savesReadsAndAuditsNothing() {
        NewsSource stored = stored(60, true);

        NewsSourceResponse response = service.update(ADMIN, stored.getId(), request(FEED, 60, true));

        assertThat(response.crawlIntervalMinutes()).isEqualTo(60);
        verify(sources, never()).save(any());
        verifyNoInteractions(rss, admin);
    }

    @Test
    void UC49_anIntervalChange_isSavedWithoutReadingTheFeed_andAuditsOnlyWhatChanged() {
        NewsSource stored = stored(60, true);

        service.update(ADMIN, stored.getId(), request(FEED, 30, true));

        verifyNoInteractions(rss);
        assertThat(stored.getCrawlIntervalMinutes()).isEqualTo(30);
        assertThat(stored.getManagedBy()).isEqualTo(ADMIN);
        AuditEntry entry = audited();
        assertThat(entry.action()).isEqualTo(AuditAction.CONFIGURATION_CHANGED);
        assertThat(entry.oldValue())
                .containsExactly(Map.entry("sourceName", "Example"), Map.entry("crawlIntervalMinutes", 60));
        assertThat(entry.newValue())
                .containsExactly(Map.entry("sourceName", "Example"), Map.entry("crawlIntervalMinutes", 30));
    }

    @Test
    void UC49_aNewAddress_isCheckedForDuplicatesAndReadBeforeItIsSaved() {
        NewsSource stored = stored(60, true);
        String moved = "https://example.com/new-feed";

        NewsSourceResponse response = service.update(ADMIN, stored.getId(), request(moved, 60, true));

        assertThat(response.feedUrl()).isEqualTo(moved);
        verify(sources, times(2)).existsByFeedUrlAndIdNot(moved, stored.getId());
        verify(rss).read(moved, null);
        assertThat(audited().newValue()).containsEntry("feedUrl", moved);
    }

    @Test
    void UC49_aNewAgent_readsTheFeedAgainWithIt() {
        NewsSource stored = stored(60, true);

        service.update(
                ADMIN,
                stored.getId(),
                new NewsSourceRequest("Example", FEED, 60, true, null, "div.body", null, "Mozilla/5.0"));

        verify(rss).read(FEED, "Mozilla/5.0");
    }

    @Test
    void UC49_anAddressAnotherSourceReads_isRefusedOnUpdate() {
        NewsSource stored = stored(60, true);
        when(sources.existsByFeedUrlAndIdNot("https://example.com/taken", stored.getId()))
                .thenReturn(true);

        assertCode(
                () -> service.update(ADMIN, stored.getId(), request("https://example.com/taken", 60, true)),
                ErrorCode.NEWS_SOURCE_ALREADY_REGISTERED);
        verifyNoInteractions(rss, admin);
    }

    @Test
    void UC49_anUnknownSource_isNotFound_everywhere() {
        UUID unknown = UUID.fromString("019b76da-a800-7e0e-8000-0000000000ff");
        when(sources.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(unknown)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(ADMIN, unknown, request(FEED)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(ADMIN, unknown)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.crawlLogs(unknown, null, null)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void UC49_aSourceArticlesCameFrom_cannotBeDeleted() {
        NewsSource stored = stored(60, true);
        when(sources.hasArticles(stored.getId())).thenReturn(true);

        assertCode(() -> service.delete(ADMIN, stored.getId()), ErrorCode.NEWS_SOURCE_IN_USE);
        verify(sources, never()).delete(any());
        verifyNoInteractions(admin);
    }

    @Test
    void UC49_aSourceWithoutArticles_isDeletedAndAuditedWithItsLastSettings() {
        NewsSource stored = stored(60, true);

        service.delete(ADMIN, stored.getId());

        verify(sources).delete(stored);
        AuditEntry entry = audited();
        assertThat(entry.action()).isEqualTo(AuditAction.CONFIGURATION_DELETED);
        assertThat(entry.entityId()).isEqualTo(stored.getId());
        assertThat(entry.oldValue()).containsEntry("feedUrl", FEED);
        assertThat(entry.newValue()).isNull();
    }

    @Test
    void UC49_theList_showsEachSourcesLatestCrawl_andWhenItIsNextDue() {
        NewsSource read = stored(60, true);
        read.markCrawled(READ_AT);
        NewsSource off = NewsSource.create(
                new NewsSourceSettings("Off", "https://off.example.com/feed", 15, false, null, null, null, null),
                ADMIN);
        off.markCrawled(READ_AT);
        when(sources.findAllForConsole()).thenReturn(List.of(read, off));
        NewsCrawlLog latest = NewsCrawlLog.record(
                read.getId(), READ_AT, READ_AT.plusSeconds(5), CrawlStatus.PARTIAL, 30, 3, "1 page timed out");
        when(crawlLogs.findLatestOf(List.of(read.getId(), off.getId()))).thenReturn(List.of(latest));

        List<NewsSourceResponse> listed = service.list();

        assertThat(listed).extracting(NewsSourceResponse::sourceName).containsExactly("Example", "Off");
        NewsSourceResponse first = listed.getFirst();
        assertThat(first.lastCrawledAt()).isEqualTo(READ_AT);
        assertThat(first.nextCrawlAt()).isEqualTo(READ_AT.plusSeconds(3600));
        assertThat(first.lastCrawl()).isEqualTo(new NewsSourceResponse.LastCrawl(READ_AT, CrawlStatus.PARTIAL, 30, 3));
        assertThat(listed.get(1).lastCrawl()).isNull();
        assertThat(listed.get(1).nextCrawlAt()).as("switched off: never due").isNull();
    }

    @Test
    void UC49_anEmptyList_asksNoCrawlLog() {
        when(sources.findAllForConsole()).thenReturn(List.of());

        assertThat(service.list()).isEmpty();
        verify(crawlLogs, never()).findLatestOf(anyCollection());
    }

    @Test
    void UC49_oneSource_comesWithItsLatestCrawl() {
        NewsSource stored = stored(60, true);
        NewsCrawlLog latest =
                NewsCrawlLog.record(stored.getId(), READ_AT, READ_AT, CrawlStatus.FAILED, null, null, "unreachable");
        when(crawlLogs.findLatestOf(List.of(stored.getId()))).thenReturn(List.of(latest));

        assertThat(service.get(stored.getId()).lastCrawl().status()).isEqualTo(CrawlStatus.FAILED);
    }

    @Test
    void UC49_theCrawlLog_isPagedLatestFirst() {
        NewsSource stored = stored(60, true);
        NewsCrawlLog log =
                NewsCrawlLog.record(stored.getId(), READ_AT, READ_AT.plusSeconds(2), CrawlStatus.SUCCESS, 10, 2, null);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(crawlLogs.findBySourceIdOrderByStartedAtDescIdDesc(any(), page.capture()))
                .thenAnswer(call -> new PageImpl<>(List.of(log), call.getArgument(1), 41));

        PageResponse<NewsCrawlLogResponse> response = service.crawlLogs(stored.getId(), 3, 20);

        assertThat(page.getValue()).isEqualTo(PageRequest.of(2, 20));
        assertThat(response.page()).isEqualTo(3);
        assertThat(response.total()).isEqualTo(41);
        assertThat(response.items())
                .containsExactly(new NewsCrawlLogResponse(
                        log.getId(), READ_AT, READ_AT.plusSeconds(2), CrawlStatus.SUCCESS, 10, 2, null));
        assertThatThrownBy(() -> service.crawlLogs(stored.getId(), 0, 20)).isInstanceOf(FieldValidationException.class);
    }

    private NewsSource stored(int interval, boolean active) {
        NewsSource source = NewsSource.create(
                new NewsSourceSettings("Example", FEED, interval, active, null, "div.body", null, null), null);
        when(sources.findById(source.getId())).thenReturn(Optional.of(source));
        return source;
    }

    private AuditEntry audited() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(admin).audit(entry.capture());
        return entry.getValue();
    }

    private static NewsSourceRequest request(String feedUrl) {
        return request(feedUrl, 60, true);
    }

    private static NewsSourceRequest request(String feedUrl, int interval, boolean active) {
        return new NewsSourceRequest("Example", feedUrl, interval, active, null, "div.body", null, null);
    }

    private static DataIntegrityViolationException integrity(String message) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(message));
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode())
                .isEqualTo(code));
    }
}
