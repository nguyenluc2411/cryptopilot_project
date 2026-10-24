package com.cryptopilot.news.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.news.client.RssClientException;
import com.cryptopilot.news.client.RssFeed;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SCR-41 end to end: the sources an administrator adds, changes and deletes, BR-49 on the interval, the feed read
 * before saving (a mock here, so no outlet is called), duplicates by normalized address, the audit trail and the crawl
 * log of each source.
 *
 * <p>Rule: UC-49; BR-49, BR-57; NSF-10.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AdminNewsSourceApiTest {

    private static final String SOURCES = "/api/v1/admin/news-sources";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private RssClient rss;

    private final List<UUID> accounts = new ArrayList<>();
    private String host;
    private UUID admin;
    private UUID trader;

    @BeforeEach
    void setUp() {
        host = "t077-" + Long.toString(System.nanoTime() % 1_000_000_000L, 36) + ".example.com";
        admin = account(UserRole.ADMIN);
        trader = account(UserRole.TRADER);
        when(rss.read(anyString(), any())).thenReturn(new RssFeed("Feed", List.of()));
    }

    @AfterEach
    void removeRows() {
        sql.sql("delete from news_article where source_id in (select source_id from news_source where feed_url like ?)")
                .param("https://" + host + "%")
                .update();
        sql.sql("delete from news_source where feed_url like ?")
                .param("https://" + host + "%")
                .update();
        sql.sql("delete from audit_log where user_id = ?").param(admin).update();
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void UC49_theConsole_isForAdministratorsOnly() throws Exception {
        as(trader, get(SOURCES)).andExpect(status().isForbidden());
        mvc.perform(get(SOURCES)).andExpect(status().isUnauthorized());
        as(trader, post(SOURCES), body("Example", "https://" + host + "/feed", 15))
                .andExpect(status().isForbidden());
    }

    @Test
    void UC49_anAdministratorAddsASource_itIsStoredNormalized_listed_andAudited() throws Exception {
        String id = create("Example Markets", "HTTPS://" + host.toUpperCase() + "/feed?utm_source=x", 30)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.feedUrl").value("https://" + host + "/feed"))
                .andExpect(jsonPath("$.sourceType").value("RSS"))
                .andExpect(jsonPath("$.crawlIntervalMinutes").value(30))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.lastCrawledAt").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID sourceId = UUID.fromString(JsonPath.read(id, "$.sourceId"));

        as(admin, get(SOURCES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sourceId == '" + sourceId + "')].sourceName")
                        .value("Example Markets"));
        assertThat(sql.sql("select managed_by from news_source where source_id = ?")
                        .param(sourceId)
                        .query(UUID.class)
                        .single())
                .isEqualTo(admin);
        assertThat(sql.sql("select count(*) from audit_log where user_id = ? and action_code = 'CONFIGURATION_CREATED'"
                                + " and entity_type = 'NEWS_SOURCE' and entity_id = ?")
                        .params(admin, sourceId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void BR49_anIntervalUnderFifteenMinutes_isRefused_andMissingFieldsAreNamed() throws Exception {
        create("Example", "https://" + host + "/feed", 14)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.crawlIntervalMinutes").value("MSG15"));
        as(admin, post(SOURCES), "{\"feedUrl\": \"https://" + host + "/feed\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sourceName").value("MSG01"))
                .andExpect(jsonPath("$.errors.crawlIntervalMinutes").value("MSG01"))
                .andExpect(jsonPath("$.errors.active").value("MSG01"));
        create("Example", "https://" + host + "/feed", 15).andExpect(status().isCreated());
    }

    @Test
    void UC49_aFeedThatCannotBeRead_isRefusedUnderItsField() throws Exception {
        when(rss.read(anyString(), any()))
                .thenThrow(new RssClientException(RssClientException.Reason.NOT_A_FEED, "a web page"));

        create("Example", "https://" + host + "/page", 15)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NEWS_FEED_UNREADABLE"))
                .andExpect(jsonPath("$.errors.feedUrl").value("MSG15"));
        assertThat(sourcesOfThisTest()).isZero();
    }

    @Test
    void UC49_theSameFeedSpelledTwoWays_isOneSource() throws Exception {
        create("First", "https://" + host + "/feed", 15).andExpect(status().isCreated());

        create("Second", "https://" + host.toUpperCase() + ":443/feed#latest", 15)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NEWS_SOURCE_ALREADY_REGISTERED"));
        assertThat(sourcesOfThisTest()).isOne();
    }

    @Test
    void UC49_anUpdateReplacesTheSettings_andTheCrawlLogIsPagedLatestFirst() throws Exception {
        UUID sourceId = idOf(create("Example", "https://" + host + "/feed", 60));
        Instant start = Instant.parse("2026-10-25T08:00:00Z");
        crawlLog(sourceId, start, "SUCCESS", 20, 5, null);
        crawlLog(sourceId, start.plusSeconds(3600), "PARTIAL", 18, 2, "2 pages timed out");
        crawlLog(sourceId, start.plusSeconds(7200), "FAILED", null, null, "unreachable");

        as(admin, put(SOURCES + "/" + sourceId), """
                        {"sourceName": "Renamed", "feedUrl": "https://%s/feed", "crawlIntervalMinutes": 30,
                         "active": false, "bodySelector": "article .body", "userAgent": ""}""".formatted(host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceName").value("Renamed"))
                .andExpect(jsonPath("$.crawlIntervalMinutes").value(30))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.bodySelector").value("article .body"))
                .andExpect(jsonPath("$.userAgent").doesNotExist())
                .andExpect(jsonPath("$.lastCrawl.status").value("FAILED"));

        as(admin, get(SOURCES + "/" + sourceId + "/crawl-logs?pageSize=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].articlesFound").doesNotExist())
                .andExpect(jsonPath("$.items[1].status").value("PARTIAL"))
                .andExpect(jsonPath("$.items[1].errorMessage").value("2 pages timed out"));
        as(admin, get(SOURCES + "/" + sourceId + "/crawl-logs?page=0")).andExpect(status().isBadRequest());
        as(admin, get(SOURCES + "/" + sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceName").value("Renamed"))
                .andExpect(jsonPath("$.lastCrawl.status").value("FAILED"));
        assertThat(sql.sql("select count(*) from audit_log where user_id = ? and action_code = 'CONFIGURATION_CHANGED'"
                                + " and entity_id = ?")
                        .params(admin, sourceId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void UC49_aSourceArticlesCameFrom_isKept_butOneWithoutArticlesIsDeletedWithItsLog() throws Exception {
        UUID used = idOf(create("Used", "https://" + host + "/used", 15));
        UUID unused = idOf(create("Unused", "https://" + host + "/unused", 15));
        article(used);
        crawlLog(unused, Instant.parse("2026-10-25T08:00:00Z"), "SUCCESS", 1, 0, null);

        as(admin, delete(SOURCES + "/" + used))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NEWS_SOURCE_IN_USE"));
        as(admin, delete(SOURCES + "/" + unused)).andExpect(status().isNoContent());

        as(admin, get(SOURCES + "/" + unused)).andExpect(status().isNotFound());
        assertThat(sql.sql("select count(*) from news_crawl_log where source_id = ?")
                        .param(unused)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(sql.sql("select count(*) from audit_log where user_id = ? and action_code = 'CONFIGURATION_DELETED'"
                                + " and entity_id = ?")
                        .params(admin, unused)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    private ResultActions create(String name, String feedUrl, int interval) throws Exception {
        return as(admin, post(SOURCES), body(name, feedUrl, interval));
    }

    private static String body(String name, String feedUrl, int interval) {
        return """
                {"sourceName": "%s", "feedUrl": "%s", "crawlIntervalMinutes": %d, "active": true,
                 "bodySelector": "div.body, main#content", "excludedSelectors": "figure, .ad"}""".formatted(name, feedUrl, interval);
    }

    private static UUID idOf(ResultActions created) throws Exception {
        return UUID.fromString(JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.sourceId"));
    }

    private long sourcesOfThisTest() {
        return sql.sql("select count(*) from news_source where feed_url like ?")
                .param("https://" + host + "%")
                .query(Long.class)
                .single();
    }

    private void crawlLog(
            UUID source, Instant startedAt, String status, Integer found, Integer inserted, String error) {
        Timestamp start = Timestamp.from(startedAt);
        sql.sql("""
                        insert into news_crawl_log (crawl_log_id, source_id, started_at, finished_at, crawl_status,
                                                    articles_found, articles_inserted, error_message, created_at,
                                                    updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), source, start, start, status, found, inserted, error, start, start)
                .update();
    }

    private void article(UUID source) {
        Timestamp now = Timestamp.from(Instant.now());
        String hash = UUID.randomUUID().toString().replace("-", "");
        sql.sql("""
                        insert into news_article (article_id, source_id, title, original_url, url_hash, title_hash,
                                                  published_at, collected_at, created_at, updated_at)
                        values (?, ?, 'A title', ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), source, "https://" + host + "/a", hash, hash, now, now, now, now)
                .update();
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return as(user, request, null);
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t077.invalid", role.name(), now, now).update();
        accounts.add(id);
        return id;
    }

    private String bearer(UUID userId) {
        String role = sql.sql("select role from user_account where user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(JwtConfig.ROLE_CLAIM, role)
                .build();
        return "Bearer "
                + jwtEncoder
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                        .getTokenValue();
    }
}
