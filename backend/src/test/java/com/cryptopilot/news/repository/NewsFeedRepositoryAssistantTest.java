package com.cryptopilot.news.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.news.model.FeedArticle;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The reads behind the AI Assistant's news (T-074) against PostgreSQL: the articles about one coin, headline mentions
 * first; the articles that name no coin; and when the outlets were last read. Hidden articles are never read.
 *
 * <p>The shared database holds other tests' articles, so each test uses a coin of its own and, for the market reads,
 * publication instants far in the future that no other article has.
 *
 * <p>Rule: NSF-10, BR-51; T-074.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class NewsFeedRepositoryAssistantTest {

    @Autowired
    private NewsFeedRepository feed;

    @Autowired
    private JdbcClient sql;

    private final List<UUID> coins = new ArrayList<>();
    private final List<UUID> sources = new ArrayList<>();
    private UUID source;
    private UUID administrator;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        source = source("Assistant Desk", false, null);
        administrator = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'ADMIN', 'ACTIVE', ?, ?)""")
                .params(administrator, administrator + "@assistant-news.invalid", at, at)
                .update();
    }

    @AfterEach
    void removeRows() {
        for (UUID id : sources) {
            sql.sql("delete from news_article where source_id = ?").param(id).update();
            sql.sql("delete from news_source where source_id = ?").param(id).update();
        }
        for (UUID coin : coins) {
            sql.sql("delete from coin where coin_id = ?").param(coin).update();
        }
        sql.sql("delete from user_account where user_id = ?")
                .param(administrator)
                .update();
    }

    @Test
    void T074_theArticlesAboutACoin_putTheHeadlineMentionsFirst_thenTheNewest_withinTheWindowAndLimit() {
        UUID coin = coin();
        UUID other = coin();
        UUID oldPrimary = article("Old headline about it", now.minus(Duration.ofHours(10)), false);
        tag(oldPrimary, coin, true);
        UUID newSecondary = article("Mentioned in passing", now.minus(Duration.ofHours(1)), false);
        tag(newSecondary, coin, false);
        UUID newPrimary = article("Fresh headline about it", now.minus(Duration.ofHours(2)), false);
        tag(newPrimary, coin, true);
        UUID tooOld = article("Last week", now.minus(Duration.ofDays(5)), false);
        tag(tooOld, coin, true);
        UUID hidden = article("Hidden by an administrator", now.minus(Duration.ofMinutes(5)), true);
        tag(hidden, coin, true);
        UUID otherCoin = article("Another coin", now.minus(Duration.ofMinutes(5)), false);
        tag(otherCoin, other, true);

        List<FeedArticle> read = feed.latestAboutCoin(coin, now.minus(Duration.ofHours(72)), 5);

        assertThat(read)
                .extracting(FeedArticle::title)
                .containsExactly("Fresh headline about it", "Old headline about it", "Mentioned in passing");
        assertThat(read.get(0).sourceName()).isEqualTo("Assistant Desk");
        assertThat(feed.latestAboutCoin(coin, now.minus(Duration.ofHours(72)), 2))
                .extracting(FeedArticle::title)
                .containsExactly("Fresh headline about it", "Old headline about it");
        assertThat(feed.latestAboutCoin(coin, now, 0)).isEmpty();
    }

    @Test
    void T074_theMarketArticles_areTheShownOnesTaggedWithNoCoin_newestFirst() {
        Instant future = now.plus(Duration.ofDays(400));
        UUID coin = coin();
        article("Exchange hacked", future.plus(Duration.ofHours(2)), false);
        article("Rates held", future.plus(Duration.ofHours(1)), false);
        article("Before the window", future.minus(Duration.ofHours(1)), false);
        article("Hidden market story", future.plus(Duration.ofHours(3)), true);
        UUID tagged = article("About one coin", future.plus(Duration.ofHours(4)), false);
        tag(tagged, coin, true);

        assertThat(feed.latestAboutMarket(future, 3))
                .extracting(FeedArticle::title)
                .containsExactly("Exchange hacked", "Rates held");
        assertThat(feed.latestAboutMarket(future, 1))
                .extracting(FeedArticle::title)
                .containsExactly("Exchange hacked");
        assertThat(feed.latestAboutMarket(future, 0)).isEmpty();
    }

    @Test
    void T074_theLastCrawl_isTheLatestReadOfAnActiveSource() {
        Instant latest = now.plus(Duration.ofDays(500));
        source("Read recently", true, latest);
        source("Read later but switched off", false, latest.plus(Duration.ofDays(1)));

        assertThat(feed.lastCrawledAt()).contains(latest);
    }

    private UUID source(String name, boolean active, Instant lastCrawledAt) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        sql.sql("""
                        insert into news_source (source_id, source_name, feed_url, source_type, crawl_interval_minutes,
                                                 is_active, last_crawled_at, created_at, updated_at)
                        values (?, ?, ?, 'RSS', 15, ?, ?, ?, ?)""")
                .params(
                        id,
                        name,
                        "https://assistant-" + id + ".example.com/feed",
                        active,
                        lastCrawledAt == null ? null : Timestamp.from(lastCrawledAt),
                        at,
                        at)
                .update();
        sources.add(id);
        return id;
    }

    private UUID article(String title, Instant publishedAt, boolean hidden) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(publishedAt);
        String hash = UUID.randomUUID().toString().replace("-", "");
        sql.sql("""
                        insert into news_article (article_id, source_id, title, original_url, url_hash, title_hash,
                                                  published_at, collected_at, created_at, updated_at, hidden_at,
                                                  hidden_by)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        source,
                        title,
                        "https://e.com/" + id,
                        hash,
                        hash,
                        at,
                        at,
                        at,
                        at,
                        hidden ? at : null,
                        hidden ? administrator : null)
                .update();
        return id;
    }

    private void tag(UUID article, UUID coin, boolean primary) {
        sql.sql("insert into news_article_coin (article_id, coin_id, is_primary) values (?, ?, ?)")
                .params(article, coin, primary)
                .update();
    }

    /** A coin of this test only, so no other article in the shared database is tagged with it. */
    private UUID coin() {
        UUID id = UUID.randomUUID();
        String symbol = "A"
                + Long.toString(Math.abs(id.getMostSignificantBits()) % 2_000_000_000L, 36)
                        .toUpperCase();
        Timestamp at = Timestamp.from(now);
        sql.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                .params(id, symbol, "Test " + symbol, at, at)
                .update();
        coins.add(id);
        return id;
    }
}
