package com.cryptopilot.news.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.news.entity.NewsCrawlLog;
import com.cryptopilot.news.entity.NewsSource;
import com.cryptopilot.news.model.NewsSourceSettings;
import com.cryptopilot.news.model.enums.CrawlStatus;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL of the news repositories against PostgreSQL: a source round-trips with its V35 settings, the due query is
 * BR-49 as {@link NewsSource#isDue(Instant)} states it, the latest crawl of each source comes in one statement, and a
 * deleted source takes its crawl log with it.
 *
 * <p>Rule: BR-49; NSF-10; UC-49.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class NewsSourceRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    @Autowired
    private NewsSourceRepository sources;

    @Autowired
    private NewsCrawlLogRepository crawlLogs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient sql;

    private final List<UUID> created = new ArrayList<>();
    private String host;

    @BeforeEach
    void setUp() {
        host = "repo-" + Long.toString(System.nanoTime() % 1_000_000_000L, 36) + ".example.com";
    }

    @AfterEach
    void removeRows() {
        for (UUID id : created) {
            sql.sql("delete from news_article where source_id = ?").param(id).update();
            sql.sql("delete from news_source where source_id = ?").param(id).update();
        }
    }

    @Test
    void UC49_aSource_roundTripsWithEverySetting() {
        NewsSourceSettings settings = new NewsSourceSettings(
                "Example",
                feed("full"),
                45,
                true,
                "https://example.com/markets",
                "div.body, main#content",
                "figure, .ad",
                "Mozilla/5.0");
        NewsSource saved = save(settings, null);

        NewsSource read = sources.findById(saved.getId()).orElseThrow();

        assertThat(read.settings()).isEqualTo(settings);
        assertThat(read.getCreatedAt()).isNotNull();
        assertThat(read.getVersion()).isZero();
        assertThat(sources.existsByFeedUrl(feed("full"))).isTrue();
        assertThat(sources.existsByFeedUrlAndIdNot(feed("full"), saved.getId())).isFalse();
    }

    @Test
    void BR49_theDueQuery_readsActiveSourcesWhoseIntervalHasElapsed_neverReadFirst() {
        UUID neverRead = save(settings("never", 15, true), null).getId();
        UUID longAgo =
                save(settings("long-ago", 15, true), NOW.minusSeconds(3600)).getId();
        UUID exactlyDue =
                save(settings("exact", 15, true), NOW.minusSeconds(15 * 60)).getId();
        UUID notYet = save(settings("not-yet", 15, true), NOW.minusSeconds(15 * 60 - 1))
                .getId();
        UUID longInterval =
                save(settings("hourly", 60, true), NOW.minusSeconds(30 * 60)).getId();
        UUID off = save(settings("off", 15, false), null).getId();

        List<UUID> due = sources.findDue(NOW).stream()
                .map(NewsSource::getId)
                .filter(created::contains)
                .toList();

        assertThat(due).containsExactly(neverRead, longAgo, exactlyDue);
        assertThat(due).doesNotContain(notYet, longInterval, off);
        for (UUID id : created) {
            NewsSource source = sources.findById(id).orElseThrow();
            assertThat(due.contains(id)).as(source.getSourceName()).isEqualTo(source.isDue(NOW));
        }
    }

    @Test
    void NSF10_theLatestCrawlOfEachSource_comesInOneStatement() {
        UUID first = save(settings("first", 15, true), null).getId();
        UUID second = save(settings("second", 15, true), null).getId();
        UUID silent = save(settings("silent", 15, true), null).getId();
        log(first, NOW.minusSeconds(7200), CrawlStatus.SUCCESS);
        NewsCrawlLog firstLatest = log(first, NOW.minusSeconds(60), CrawlStatus.FAILED);
        NewsCrawlLog secondLatest = log(second, NOW.minusSeconds(600), CrawlStatus.PARTIAL);
        log(second, NOW.minusSeconds(6000), CrawlStatus.SUCCESS);

        List<NewsCrawlLog> latest = crawlLogs.findLatestOf(List.of(first, second, silent));

        assertThat(latest)
                .extracting(NewsCrawlLog::getId)
                .containsExactlyInAnyOrder(firstLatest.getId(), secondLatest.getId());
    }

    @Test
    void UC49_articlesBindASource_andDeletingOneTakesItsCrawlLog() {
        NewsSource used = save(settings("used", 15, true), null);
        NewsSource unused = save(settings("unused", 15, true), null);
        Timestamp now = Timestamp.from(NOW);
        sql.sql("""
                        insert into news_article (article_id, source_id, title, original_url, url_hash, title_hash,
                                                  published_at, collected_at, created_at, updated_at)
                        values (?, ?, 'A title', 'https://example.com/a', ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), used.getId(), host, host, now, now, now, now)
                .update();
        log(unused.getId(), NOW, CrawlStatus.SUCCESS);

        assertThat(sources.hasArticles(used.getId())).isTrue();
        assertThat(sources.hasArticles(unused.getId())).isFalse();
        inTransaction(() -> {
            sources.delete(sources.findById(unused.getId()).orElseThrow());
            return null;
        });

        assertThat(sources.findById(unused.getId())).isEmpty();
        assertThat(sql.sql("select count(*) from news_crawl_log where source_id = ?")
                        .param(unused.getId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private NewsSource save(NewsSourceSettings settings, Instant lastCrawledAt) {
        NewsSource source = NewsSource.create(settings, null);
        if (lastCrawledAt != null) {
            source.markCrawled(lastCrawledAt);
        }
        created.add(source.getId());
        return inTransaction(() -> sources.save(source));
    }

    private NewsCrawlLog log(UUID source, Instant startedAt, CrawlStatus status) {
        NewsCrawlLog log = status == CrawlStatus.FAILED
                ? NewsCrawlLog.record(source, startedAt, startedAt.plusSeconds(1), status, null, null, "down")
                : NewsCrawlLog.record(
                        source,
                        startedAt,
                        startedAt.plusSeconds(1),
                        status,
                        5,
                        1,
                        status == CrawlStatus.PARTIAL ? "1 page failed" : null);
        return inTransaction(() -> crawlLogs.save(log));
    }

    private NewsSourceSettings settings(String name, int interval, boolean active) {
        return new NewsSourceSettings(name, feed(name), interval, active, null, null, null, null);
    }

    private String feed(String path) {
        return "https://" + host + "/" + path;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
