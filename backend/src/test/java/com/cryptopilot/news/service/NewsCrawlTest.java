package com.cryptopilot.news.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.cryptopilot.market.MarketTestData;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientException;
import com.cryptopilot.news.client.RssFeed;
import com.cryptopilot.news.client.RssItem;
import com.cryptopilot.news.entity.NewsSource;
import com.cryptopilot.news.model.NewsSourceSettings;
import com.cryptopilot.news.repository.NewsSourceRepository;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NSF-10 against PostgreSQL, the feed reader a mock: a due source is crawled once per interval, its new articles are
 * stored with their coins, a URL or a title already stored within 48 hours is skipped, every crawl is logged, and five
 * failed crawls in a row reach the administrators as a notice.
 *
 * <p>Rule: NSF-10; BR-48, BR-49; TECHNICAL_DESIGN 7.13.
 */
@SpringBootTest(properties = "cryptopilot.news.crawl.coin-names.ZORQX=Zorqcoin")
@Import(TestcontainersConfig.class)
class NewsCrawlTest {

    private static final String SYMBOL = "ZORQX";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private CrawlService crawl;

    @Autowired
    private NewsSourceRepository sources;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient sql;

    @MockitoBean
    private RssClient rss;

    private final List<UUID> created = new ArrayList<>();
    private final List<UUID> accounts = new ArrayList<>();
    private MarketTestData market;
    private UUID pair;
    private String host;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        host = "crawl-" + Long.toString(System.nanoTime() % 1_000_000_000L, 36) + ".example.com";
        market = new MarketTestData(sql, now);
        sql.sql("delete from crypto_pair where symbol = ?")
                .param(SYMBOL + "USDT")
                .update();
        pair = market.pair(SYMBOL + "USDT", true, false, "TRADING", null, 1);
        // Every other source left due in the database fails quietly instead of being read.
        doThrow(new RssClientException(RssClientException.Reason.UNREACHABLE, "not this test's feed"))
                .when(rss)
                .read(anyString(), any());
    }

    @AfterEach
    void removeRows() {
        for (UUID source : created) {
            sql.sql("delete from notification where reference_id = ?")
                    .param(source)
                    .update();
            sql.sql("delete from news_article where source_id = ?")
                    .param(source)
                    .update();
            sql.sql("delete from news_source where source_id = ?").param(source).update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from notification where user_id = ?").param(account).update();
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
        market.remove(pair);
    }

    @Test
    void NSF10_aDueSource_isCrawled_itsArticlesStoredWithTheirCoins_andTheCrawlLogged() {
        UUID source = source("markets");
        doReturn(new RssFeed(
                        "Markets",
                        List.of(
                                item(SYMBOL + " jumps 20% " + host, "https://" + host + "/a", now.minusSeconds(600)),
                                item("Tether and zorqcoin: one step closer " + host, "https://" + host + "/b", null),
                                item(
                                        "Copied headline " + host,
                                        "https://" + host.toUpperCase() + "/a?utm_source=rss#x",
                                        now.minusSeconds(60)))))
                .when(rss)
                .read(eq(feed("markets")), any());

        crawl.crawlDue();

        assertThat(articleTitles(source))
                .containsExactlyInAnyOrder(
                        SYMBOL + " jumps 20% " + host, "Tether and zorqcoin: one step closer " + host);
        assertThat(coinsOf("https://" + host + "/a")).containsExactly(Map.entry(SYMBOL, true));
        assertThat(coinsOf("https://" + host + "/b"))
                .as("the name in lower case tags the coin; 'one' in lower case tags nothing")
                .containsExactlyInAnyOrderEntriesOf(Map.of("USDT", true, SYMBOL, false));
        Map<String, Object> log = sql.sql("""
                        select crawl_status, articles_found, articles_inserted, error_message
                          from news_crawl_log where source_id = ?""").param(source).query().singleRow();
        assertThat(log)
                .containsEntry("crawl_status", "SUCCESS")
                .containsEntry("articles_found", 3)
                .containsEntry("articles_inserted", 2);
        assertThat(sql.sql("select last_crawled_at is not null from news_source where source_id = ?")
                        .param(source)
                        .query(Boolean.class)
                        .single())
                .isTrue();

        crawl.crawlDue();

        assertThat(logsOf(source)).as("read once per interval (BR-49)").isOne();
    }

    @Test
    void BR48_theSameTitleWithin48Hours_isOneStory_butLaterItIsAnother() {
        String title = "Same story " + host;
        UUID first = source("first");
        UUID second = source("second");
        doReturn(new RssFeed("First", List.of(item(title, "https://" + host + "/1", now.minusSeconds(3600)))))
                .when(rss)
                .read(eq(feed("first")), any());
        doReturn(new RssFeed(
                        "Second",
                        List.of(
                                item(
                                        title.toUpperCase() + "!",
                                        "https://" + host + "/2",
                                        now.minus(Duration.ofHours(30))),
                                item(title, "https://" + host + "/3", now.minus(Duration.ofHours(100))))))
                .when(rss)
                .read(eq(feed("second")), any());

        crawl.crawlDue();

        assertThat(articleTitles(first)).hasSize(1);
        assertThat(sql.sql("select original_url from news_article where source_id = ?")
                        .param(second)
                        .query(String.class)
                        .list())
                .as("30 hours apart is the same story, 99 hours apart is not")
                .containsExactly("https://" + host + "/3");
        assertThat(sql.sql("select articles_found || '/' || articles_inserted from news_crawl_log where source_id = ?")
                        .param(second)
                        .query(String.class)
                        .single())
                .isEqualTo("2/1");
    }

    @Test
    void NSF10_fiveFailedCrawlsInARow_tellTheAdministrators() {
        UUID admin = administrator();
        UUID source = source("down");
        doThrow(new RssClientException(RssClientException.Reason.HTTP_STATUS, "answered HTTP 503"))
                .when(rss)
                .read(eq(feed("down")), any());

        for (int run = 1; run <= 5; run++) {
            crawl.crawlDue();
            assertThat(notificationsOf(admin, source)).isZero();
            if (run < 5) {
                sql.sql("update news_source set last_crawled_at = null where source_id = ?")
                        .param(source)
                        .update();
            }
        }

        assertThat(sql.sql("select count(*) from news_crawl_log where source_id = ? and crawl_status = 'FAILED'")
                        .param(source)
                        .query(Long.class)
                        .single())
                .isEqualTo(5);
        await().atMost(WAIT).until(() -> notificationsOf(admin, source) == 1);
        assertThat(sql.sql("select notification_type || '|' || reference_type || '|' || content from notification"
                                + " where user_id = ? and reference_id = ?")
                        .params(admin, source)
                        .query(String.class)
                        .single())
                .startsWith("SYSTEM|NEWS_SOURCE|Down could not be read 5 times in a row.")
                .contains("HTTP_STATUS: answered HTTP 503");
    }

    private UUID source(String path) {
        NewsSource source = NewsSource.create(
                new NewsSourceSettings(
                        Character.toUpperCase(path.charAt(0)) + path.substring(1),
                        feed(path),
                        15,
                        true,
                        null,
                        null,
                        null,
                        null),
                null);
        created.add(source.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> sources.save(source));
        return source.getId();
    }

    private String feed(String path) {
        return "https://" + host + "/feed/" + path;
    }

    private static RssItem item(String title, String link, Instant publishedAt) {
        return new RssItem(title, link, publishedAt, "<p>" + title + "</p>", List.of());
    }

    private List<String> articleTitles(UUID source) {
        return sql.sql("select title from news_article where source_id = ?")
                .param(source)
                .query(String.class)
                .list();
    }

    private Map<String, Boolean> coinsOf(String url) {
        Map<String, Boolean> coins = new LinkedHashMap<>();
        sql.sql("""
                        select c.symbol, ac.is_primary from news_article_coin ac
                          join news_article a on a.article_id = ac.article_id
                          join coin c on c.coin_id = ac.coin_id
                         where a.original_url = ?""")
                .param(url)
                .query((row, index) -> Map.entry(row.getString(1), row.getBoolean(2)))
                .list()
                .forEach(entry -> coins.put(entry.getKey(), entry.getValue()));
        return coins;
    }

    private long logsOf(UUID source) {
        return sql.sql("select count(*) from news_crawl_log where source_id = ?")
                .param(source)
                .query(Long.class)
                .single();
    }

    private long notificationsOf(UUID admin, UUID source) {
        return sql.sql("select count(*) from notification where user_id = ? and reference_id = ?")
                .params(admin, source)
                .query(Long.class)
                .single();
    }

    private UUID administrator() {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'ADMIN', 'ACTIVE', ?, ?)""").params(id, id + "@t078.invalid", at, at).update();
        accounts.add(id);
        return id;
    }
}
