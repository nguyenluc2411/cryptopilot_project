package com.cryptopilot.news.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cryptopilot.ai.NewsSummaryApi;
import com.cryptopilot.ai.NewsSummaryRequest;
import com.cryptopilot.ai.NewsSummaryResult;
import com.cryptopilot.news.client.RssClient;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * NSF-11 against PostgreSQL with the application's own configuration — the model, the prompt file — and the AI Service
 * a mock: only recent articles without a summary are sent, the answer is stored with its sentiment, and an article that
 * has one is never sent again.
 *
 * <p>Rule: NSF-11; BR-48.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class NewsSummaryTest {

    @Autowired
    private NewsSummaryService summaries;

    @Autowired
    private JdbcClient sql;

    @MockitoBean
    private NewsSummaryApi ai;

    @MockitoBean
    private RssClient rss;

    private UUID source;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        source = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        sql.sql("""
                        insert into news_source (source_id, source_name, feed_url, source_type, crawl_interval_minutes,
                                                 is_active, created_at, updated_at)
                        values (?, 'Summary Test', ?, 'RSS', 15, false, ?, ?)""")
                .params(source, "https://summary-" + source + ".example.com/feed", at, at)
                .update();
        // An answer true to each article: the checks refuse a summary whose figures the article does not hold.
        when(ai.summarize(any())).thenAnswer(call -> {
            NewsSummaryRequest request = call.getArgument(0);
            String summary =
                    request.title().startsWith("Bitcoin") ? "Bitcoin đi ngang quanh $81,700." : "Tóm tắt ngắn.";
            return new NewsSummaryResult(summary, "NEUTRAL", request.model(), 300, 40);
        });
    }

    @AfterEach
    void removeRows() {
        sql.sql("delete from news_article where source_id = ?").param(source).update();
        sql.sql("delete from news_source where source_id = ?").param(source).update();
    }

    @Test
    void NSF11_onlyRecentArticlesWithoutASummary_areSent_andTheAnswerIsStored() {
        UUID fresh = article("Bitcoin holds $81,700", "Bitcoin traded near $81,700.", now.minusSeconds(600), null);
        UUID old = article("Old story", "Old.", now.minus(Duration.ofDays(8)), null);
        UUID done = article("Done", "Done.", now.minusSeconds(60), "Đã có tóm tắt.");

        summaries.summarizeDue();

        ArgumentCaptor<NewsSummaryRequest> sent = ArgumentCaptor.forClass(NewsSummaryRequest.class);
        verify(ai, atLeastOnce()).summarize(sent.capture());
        List<String> titles =
                sent.getAllValues().stream().map(NewsSummaryRequest::title).toList();
        assertThat(titles).contains("Bitcoin holds $81,700").doesNotContain("Old story", "Done");
        NewsSummaryRequest request = sent.getAllValues().stream()
                .filter(r -> r.title().equals("Bitcoin holds $81,700"))
                .findFirst()
                .orElseThrow();
        assertThat(request.model()).as("from application.yml").isEqualTo("gemini-3.5-flash-lite");
        assertThat(request.systemPrompt())
                .as("the prompt file, with its placeholders filled")
                .contains("Write the summary in Vietnamese, in at most 60 words")
                .doesNotContain("{language}", "{maxWords}");
        assertThat(request.text()).isEqualTo("Bitcoin traded near $81,700.");
        verifyNoInteractions(rss);

        assertThat(row(fresh))
                .containsEntry("ai_summary", "Bitcoin đi ngang quanh $81,700.")
                .containsEntry("sentiment_label", "NEUTRAL");
        assertThat(row(old)).containsEntry("ai_summary", null);
        assertThat(row(done)).containsEntry("ai_summary", "Đã có tóm tắt.").containsEntry("sentiment_label", null);
    }

    @Test
    void NSF11_anArticleWithASummary_isNeverSentAgain() {
        UUID fresh = article("Ether ETF flows " + source, "Ether ETFs saw flows.", now.minusSeconds(60), null);

        summaries.summarizeDue();
        clearInvocations(ai);
        summaries.summarizeDue();

        ArgumentCaptor<NewsSummaryRequest> sent = ArgumentCaptor.forClass(NewsSummaryRequest.class);
        verify(ai, atLeast(0)).summarize(sent.capture());
        assertThat(sent.getAllValues())
                .extracting(NewsSummaryRequest::title)
                .doesNotContain("Ether ETF flows " + source);
        assertThat(row(fresh)).containsEntry("sentiment_label", "NEUTRAL");
    }

    @Test
    void NSF11_theUnsummarizedArticles_haveTheirOwnIndex() {
        assertThat(sql.sql("select indexdef from pg_indexes where indexname = 'idx_news_article_unsummarized'")
                        .query(String.class)
                        .single())
                .contains("published_at DESC")
                .contains("WHERE (ai_summary IS NULL)");
    }

    private UUID article(String title, String excerpt, Instant collectedAt, String summary) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(collectedAt);
        String hash = UUID.randomUUID().toString().replace("-", "");
        sql.sql("""
                        insert into news_article (article_id, source_id, title, original_url, url_hash, title_hash,
                                                  excerpt, ai_summary, published_at, collected_at, created_at,
                                                  updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, source, title, "https://e.com/" + id, hash, hash, excerpt, summary, at, at, at, at)
                .update();
        return id;
    }

    private Map<String, Object> row(UUID article) {
        return sql.sql("select ai_summary, sentiment_label from news_article where article_id = ?")
                .param(article)
                .query()
                .singleRow();
    }
}
