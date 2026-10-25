package com.cryptopilot.news.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * SCR-28 end to end against PostgreSQL: the same feed for a Trader on any plan (D-126), the AI's Vietnamese text once
 * an article is summarized and the collected text until then (D-127), the coin, sentiment and period filters, paging,
 * the 24-hour summary bar, and Traders only.
 *
 * <p>Each test tags its articles with coins of its own, so the shared database's other articles never match the coin
 * filter; the summary bar, which counts every article, is read before and after.
 *
 * <p>Rule: UC-34; SRS 3.8.1; CR-04; D-126, D-127.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class NewsApiTest {

    private static final String NEWS = "/api/v1/news";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<UUID> coins = new ArrayList<>();
    private UUID source;
    private UUID trader;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        trader = account(UserRole.TRADER);
        source = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        sql.sql("""
                        insert into news_source (source_id, source_name, feed_url, source_type, crawl_interval_minutes,
                                                 is_active, created_at, updated_at)
                        values (?, 'Feed Test Desk', ?, 'RSS', 15, false, ?, ?)""")
                .params(source, "https://t080-" + source + ".example.com/feed", at, at)
                .update();
    }

    @AfterEach
    void removeRows() {
        sql.sql("delete from news_article where source_id = ?").param(source).update();
        sql.sql("delete from news_source where source_id = ?").param(source).update();
        for (UUID coin : coins) {
            sql.sql("delete from coin where coin_id = ?").param(coin).update();
        }
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC34_theFeed_isForTradersOnly() throws Exception {
        mvc.perform(get(NEWS)).andExpect(status().isUnauthorized());
        mvc.perform(get(NEWS + "/sentiment-summary")).andExpect(status().isUnauthorized());
        UUID admin = account(UserRole.ADMIN);
        as(admin, NEWS).andExpect(status().isForbidden());
        as(admin, NEWS + "/sentiment-summary").andExpect(status().isForbidden());
    }

    @Test
    void D118_aSummarizedArticleReadsInVietnamese_andAnotherShowsWhatWasCollected() throws Exception {
        String coin = coin();
        String other = coin();
        UUID summarized = article(
                "Bitcoin ETFs see $635M outflows",
                "ETF outflows reached $635 million.",
                now.minusSeconds(600),
                "ETF Bitcoin bị rút $635 triệu",
                "Quỹ ETF Bitcoin bị rút ròng $635 triệu.",
                "BEARISH");
        tag(summarized, other, false);
        tag(summarized, coin, true);
        tag(
                article("Ether staking grows", "Staking deposits rose.", now.minusSeconds(60), null, null, null),
                coin,
                true);

        as(trader, NEWS + "?coin=" + coin.toLowerCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.items[0].title").value("Ether staking grows"))
                .andExpect(jsonPath("$.items[0].summary").value("Staking deposits rose."))
                .andExpect(jsonPath("$.items[0].aiSummarized").value(false))
                .andExpect(jsonPath("$.items[0].sentiment").doesNotExist())
                .andExpect(jsonPath("$.items[1].articleId").value(summarized.toString()))
                .andExpect(jsonPath("$.items[1].title").value("ETF Bitcoin bị rút $635 triệu"))
                .andExpect(jsonPath("$.items[1].originalTitle").value("Bitcoin ETFs see $635M outflows"))
                .andExpect(jsonPath("$.items[1].summary").value("Quỹ ETF Bitcoin bị rút ròng $635 triệu."))
                .andExpect(jsonPath("$.items[1].aiSummarized").value(true))
                .andExpect(jsonPath("$.items[1].sentiment").value("BEARISH"))
                .andExpect(jsonPath("$.items[1].source").value("Feed Test Desk"))
                .andExpect(jsonPath("$.items[1].originalUrl").value("https://e.com/" + summarized))
                .andExpect(jsonPath("$.items[1].coins[0].symbol").value(coin))
                .andExpect(jsonPath("$.items[1].coins[0].primary").value(true))
                .andExpect(jsonPath("$.items[1].coins[1].symbol").value(other))
                .andExpect(jsonPath("$.items[1].coins[1].primary").value(false));
    }

    @Test
    void UC34_theSentimentAndPeriodFilters_narrowTheFeed_sevenDaysByDefault() throws Exception {
        String coin = coin();
        tag(article("Today bullish", "a", now.minus(Duration.ofHours(2)), "t", "s", "BULLISH"), coin, true);
        tag(article("Three days bearish", "b", now.minus(Duration.ofDays(3)), "t", "s", "BEARISH"), coin, true);
        tag(article("Twenty days", "c", now.minus(Duration.ofDays(20)), null, null, null), coin, true);
        tag(article("Forty days", "d", now.minus(Duration.ofDays(40)), null, null, null), coin, true);

        assertThat(titles("?coin=" + coin)).containsExactly("Today bullish", "Three days bearish");
        assertThat(titles("?coin=" + coin + "&range=24h")).containsExactly("Today bullish");
        assertThat(titles("?coin=" + coin + "&range=30D"))
                .containsExactly("Today bullish", "Three days bearish", "Twenty days");
        as(trader, NEWS + "?coin=" + coin + "&range=30d&sentiment=bearish")
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].originalTitle").value("Three days bearish"));
    }

    @Test
    void CR04_theFeedIsPaged_andBadParametersAreNamed() throws Exception {
        String coin = coin();
        for (int i = 1; i <= 3; i++) {
            tag(article("Story " + i, "x", now.minusSeconds(60L * i), null, null, null), coin, true);
        }

        as(trader, NEWS + "?coin=" + coin + "&page=2&pageSize=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Story 3"));
        as(trader, NEWS + "?coin=" + coin + "&sentiment=NEUTRAL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        as(trader, NEWS + "?range=1y")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.range").value("MSG15"));
        as(trader, NEWS + "?sentiment=MIXED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sentiment").value("MSG15"));
        as(trader, NEWS + "?pageSize=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").value("MSG15"));
        as(trader, NEWS + "?coin=NOSUCHCOIN" + coin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void UC34_theSummaryBar_countsTheLabelledArticlesOfTheLast24Hours() throws Exception {
        long[] before = summary();
        article("New bull", "a", now.minus(Duration.ofHours(1)), "t", "s", "BULLISH");
        article("New bull 2", "a", now.minus(Duration.ofHours(23)), "t", "s", "BULLISH");
        article("New bear", "b", now.minus(Duration.ofHours(3)), "t", "s", "BEARISH");
        article("Old neutral", "c", now.minus(Duration.ofHours(30)), "t", "s", "NEUTRAL");
        article("Unlabelled", "d", now.minus(Duration.ofHours(1)), null, null, null);

        long[] after = summary();

        assertThat(new long[] {after[0] - before[0], after[1] - before[1], after[2] - before[2]})
                .containsExactly(2, 0, 1);
    }

    private UUID article(
            String title, String excerpt, Instant publishedAt, String aiTitle, String aiSummary, String sentiment) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(publishedAt);
        String hash = UUID.randomUUID().toString().replace("-", "");
        sql.sql("""
                        insert into news_article (article_id, source_id, title, original_url, url_hash, title_hash,
                                                  excerpt, ai_title, ai_summary, sentiment_label, published_at,
                                                  collected_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        source,
                        title,
                        "https://e.com/" + id,
                        hash,
                        hash,
                        excerpt,
                        aiTitle,
                        aiSummary,
                        sentiment,
                        at,
                        at,
                        at,
                        at)
                .update();
        return id;
    }

    private void tag(UUID article, String symbol, boolean primary) {
        sql.sql("""
                        insert into news_article_coin (article_id, coin_id, is_primary)
                        select ?, coin_id, ? from coin where symbol = ?""").params(article, primary, symbol).update();
    }

    /** A coin of this test only, so no other article in the shared database is tagged with it. */
    private String coin() {
        UUID id = UUID.randomUUID();
        String symbol = "T"
                + Long.toString(Math.abs(id.getMostSignificantBits()) % 2_000_000_000L, 36)
                        .toUpperCase();
        Timestamp at = Timestamp.from(now);
        sql.sql("insert into coin (coin_id, symbol, coin_name, created_at, updated_at) values (?, ?, ?, ?, ?)")
                .params(id, symbol, "Test " + symbol, at, at)
                .update();
        coins.add(id);
        return symbol;
    }

    /** The collected headlines of a feed page, in feed order. */
    private List<String> titles(String query) throws Exception {
        String body = as(trader, NEWS + query)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.items[*].originalTitle");
    }

    private long[] summary() throws Exception {
        String body = as(trader, NEWS + "/sentiment-summary")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new long[] {
            ((Number) JsonPath.read(body, "$.bullish")).longValue(),
            ((Number) JsonPath.read(body, "$.neutral")).longValue(),
            ((Number) JsonPath.read(body, "$.bearish")).longValue()
        };
    }

    private ResultActions as(UUID user, String url) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t080.invalid", role.name(), at, at).update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId) {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
        Instant issued = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
