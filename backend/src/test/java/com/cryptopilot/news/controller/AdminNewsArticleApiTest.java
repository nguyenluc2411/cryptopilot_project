package com.cryptopilot.news.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cryptopilot.ai.AiServiceException;
import com.cryptopilot.ai.NewsSummaryApi;
import com.cryptopilot.ai.NewsSummaryResult;
import com.cryptopilot.auth.config.JwtConfig;
import com.cryptopilot.support.TestcontainersConfig;
import com.cryptopilot.user.model.enums.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The news page of the administration console end to end against PostgreSQL (T-080b): each article's status worked
 * out from its row, hiding one takes it out of the Traders' feed and showing it puts it back (both audited, BR-57),
 * and asking for a summary again stores the model's answer or its reason. The AI Service is a mock.
 *
 * <p>Rule: NSF-11; BR-57; D-128.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AdminNewsArticleApiTest {

    private static final String ARTICLES = "/api/v1/admin/news-articles";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private NewsSummaryApi ai;

    private final List<UUID> accounts = new ArrayList<>();
    private UUID source;
    private UUID admin;
    private UUID trader;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        admin = account(UserRole.ADMIN);
        trader = account(UserRole.TRADER);
        source = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        sql.sql("""
                        insert into news_source (source_id, source_name, feed_url, source_type, crawl_interval_minutes,
                                                 is_active, created_at, updated_at)
                        values (?, 'Console Desk', ?, 'RSS', 15, false, ?, ?)""")
                .params(source, "https://t080b-" + source + ".example.com/feed", at, at)
                .update();
    }

    @AfterEach
    void removeRows() {
        sql.sql("delete from news_article where source_id = ?").param(source).update();
        sql.sql("delete from news_source where source_id = ?").param(source).update();
        sql.sql("delete from audit_log where user_id = ?").param(admin).update();
        for (UUID account : accounts) {
            sql.sql("delete from user_account where user_id = ?").param(account).update();
        }
    }

    @Test
    void T080b_thePage_isForAdministratorsOnly() throws Exception {
        UUID article = article("A story", null, 0, null);
        mvc.perform(get(ARTICLES)).andExpect(status().isUnauthorized());
        as(trader, get(ARTICLES)).andExpect(status().isForbidden());
        as(trader, post(ARTICLES + "/" + article + "/hide")).andExpect(status().isForbidden());
        as(trader, post(ARTICLES + "/" + article + "/summarize")).andExpect(status().isForbidden());
    }

    @Test
    void D119_eachArticle_hasTheStatusItsRowSays_andTheListFiltersByIt() throws Exception {
        UUID pending = article("Pending", null, 1, "LLM_REJECTED: blocked");
        UUID failed = article("Failed", null, 3, "the summary tells the reader to trade");
        UUID summarized = article("Summarized", "Tóm tắt.", 1, null);

        as(admin, get(ARTICLES + "?sourceId=" + source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[?(@.articleId == '" + pending + "')].status")
                        .value("PENDING"))
                .andExpect(jsonPath("$.items[?(@.articleId == '" + pending + "')].summaryAttempts")
                        .value(1))
                .andExpect(jsonPath("$.items[?(@.articleId == '" + pending + "')].summaryError")
                        .value("LLM_REJECTED: blocked"))
                .andExpect(jsonPath("$.items[?(@.articleId == '" + failed + "')].status")
                        .value("FAILED"))
                .andExpect(jsonPath("$.items[?(@.articleId == '" + summarized + "')].status")
                        .value("SUMMARIZED"))
                .andExpect(jsonPath("$.items[?(@.articleId == '" + summarized + "')].source")
                        .value("Console Desk"));
        as(admin, get(ARTICLES + "?sourceId=" + source + "&status=failed"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].articleId").value(failed.toString()));
        as(admin, get(ARTICLES + "?status=STUCK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").value("MSG15"));
    }

    @Test
    void T080b_aHiddenArticle_leavesTheFeed_andComesBackWhenRestored_bothAudited() throws Exception {
        UUID article = article("Misleading story", "Tóm tắt.", 1, null);

        as(admin, post(ARTICLES + "/" + article + "/hide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"))
                .andExpect(jsonPath("$.hiddenAt").exists());
        as(admin, post(ARTICLES + "/" + article + "/hide")).andExpect(status().isOk());
        assertThat(feedIds()).doesNotContain(article.toString());
        assertThat(sql.sql("select hidden_by from news_article where article_id = ?")
                        .param(article)
                        .query(UUID.class)
                        .single())
                .isEqualTo(admin);

        as(admin, post(ARTICLES + "/" + article + "/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUMMARIZED"))
                .andExpect(jsonPath("$.hiddenAt").doesNotExist());
        assertThat(feedIds()).contains(article.toString());
        assertThat(audits("NEWS_ARTICLE_HIDDEN", article))
                .as("hiding twice is one decision")
                .isOne();
        assertThat(audits("NEWS_ARTICLE_RESTORED", article)).isOne();
        as(admin, post(ARTICLES + "/" + UUID.randomUUID() + "/hide")).andExpect(status().isNotFound());
    }

    @Test
    void D119_summarizeAgain_storesTheAnswer_orItsReason_andTheServiceDownIs503() throws Exception {
        UUID article = article("Bitcoin holds $81,700", null, 3, "the summary tells the reader to trade");
        when(ai.summarize(any()))
                .thenReturn(new NewsSummaryResult(
                        "Bitcoin giữ mức $81,700", "Bitcoin đi ngang quanh $81,700.", "NEUTRAL", "m", 10, 5));

        as(admin, post(ARTICLES + "/" + article + "/summarize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUMMARIZED"))
                .andExpect(jsonPath("$.aiTitle").value("Bitcoin giữ mức $81,700"))
                .andExpect(jsonPath("$.aiSummary").value("Bitcoin đi ngang quanh $81,700."))
                .andExpect(jsonPath("$.sentiment").value("NEUTRAL"))
                .andExpect(jsonPath("$.summaryAttempts").value(4))
                .andExpect(jsonPath("$.summaryError").doesNotExist());

        when(ai.summarize(any()))
                .thenReturn(new NewsSummaryResult("t", "Bitcoin chạm $99,000.", "BULLISH", "m", 10, 5));
        as(admin, post(ARTICLES + "/" + article + "/summarize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUMMARIZED"))
                .andExpect(jsonPath("$.aiSummary").value("Bitcoin đi ngang quanh $81,700."))
                .andExpect(jsonPath("$.summaryAttempts").value(5))
                .andExpect(jsonPath("$.summaryError").value("these figures are not in the article: 99,000"));

        when(ai.summarize(any())).thenThrow(new AiServiceException(AiServiceException.NOT_CONFIGURED, false, "no"));
        as(admin, post(ARTICLES + "/" + article + "/summarize"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NEWS_SUMMARY_UNAVAILABLE"));
    }

    private UUID article(String title, String aiSummary, int attempts, String error) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(now.minusSeconds(60));
        String hash = UUID.randomUUID().toString().replace("-", "");
        sql.sql("""
                        insert into news_article (article_id, source_id, title, original_url, url_hash, title_hash,
                                                  excerpt, ai_summary, sentiment_label, summary_attempts,
                                                  summary_error, published_at, collected_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        id,
                        source,
                        title,
                        "https://e.com/" + id,
                        hash,
                        hash,
                        title + ".",
                        aiSummary,
                        aiSummary == null ? null : "NEUTRAL",
                        attempts,
                        error,
                        at,
                        at,
                        at,
                        at)
                .update();
        return id;
    }

    /** The article ids of the Traders' feed, newest first, 100 of them. */
    private List<String> feedIds() throws Exception {
        String body = as(trader, get("/api/v1/news?pageSize=100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.items[*].articleId");
    }

    private long audits(String action, UUID article) {
        return sql.sql("select count(*) from audit_log where user_id = ? and action_code = ? and entity_type = ?"
                        + " and entity_id = ?")
                .params(admin, action, "NEWS_ARTICLE", article)
                .query(Long.class)
                .single();
    }

    private ResultActions as(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private UUID account(UserRole role) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', ?, 'ACTIVE', ?, ?)""").params(id, id + "@t080b.invalid", role.name(), at, at).update();
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
