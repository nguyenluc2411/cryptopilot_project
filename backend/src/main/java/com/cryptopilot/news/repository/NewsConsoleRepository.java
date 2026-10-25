package com.cryptopilot.news.repository;

import com.cryptopilot.news.model.ConsoleArticle;
import com.cryptopilot.news.model.enums.NewsArticleStatus;
import com.cryptopilot.news.model.enums.SentimentLabel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the articles for the administration console (T-080b), hidden ones included, each with its status worked out
 * in the query by the rules {@link NewsArticleStatus} states: hidden first, then summarized, then failed once NSF-11
 * has used its attempts, else pending. A page is one query in feed order plus one count.
 *
 * <p>Rule: NSF-11; CR-04; D-128.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class NewsConsoleRepository {

    private static final String STATUS = "case when a.hidden_at is not null then 'HIDDEN'"
            + " when a.ai_summary is not null then 'SUMMARIZED'"
            + " when a.summary_attempts >= :maxAttempts then 'FAILED'"
            + " else 'PENDING' end";

    private static final String SELECT = "select a.article_id, a.source_id, s.source_name, a.title, a.ai_title,"
            + " a.original_url, a.excerpt, a.ai_summary, a.sentiment_label, a.published_at, a.collected_at,"
            + " a.summary_attempts, a.summary_error, a.summary_attempted_at, a.hidden_at, "
            + STATUS
            + " as status from news_article a join news_source s on s.source_id = a.source_id";

    private final JdbcClient sql;

    /**
     * One page of the articles, newest first.
     *
     * @param status only articles in this status, or {@code null} for all
     * @param sourceId only articles of this source, or {@code null} for all
     * @param maxAttempts the attempts after which an article without a summary is {@code FAILED}
     */
    public Page<ConsoleArticle> page(NewsArticleStatus status, UUID sourceId, int maxAttempts, Pageable page) {
        Map<String, Object> params = new HashMap<>();
        params.put("maxAttempts", maxAttempts);
        StringBuilder where = new StringBuilder(" where true");
        if (status != null) {
            where.append(" and (").append(STATUS).append(") = :status");
            params.put("status", status.name());
        }
        if (sourceId != null) {
            where.append(" and a.source_id = :source");
            params.put("source", sourceId);
        }
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", page.getPageSize());
        paged.put("offset", page.getOffset());
        List<ConsoleArticle> articles = sql.sql(
                        SELECT + where + " order by a.published_at desc, a.article_id desc limit :limit offset :offset")
                .params(paged)
                .query((row, index) -> article(row))
                .list();
        long total = sql.sql("select count(*) from news_article a" + where)
                .params(params)
                .query(Long.class)
                .single();
        return new PageImpl<>(articles, page, total);
    }

    /** One article, hidden or not. */
    public Optional<ConsoleArticle> find(UUID articleId, int maxAttempts) {
        return sql.sql(SELECT + " where a.article_id = :id")
                .param("id", articleId)
                .param("maxAttempts", maxAttempts)
                .query((row, index) -> article(row))
                .optional();
    }

    private static ConsoleArticle article(ResultSet row) throws SQLException {
        String sentiment = row.getString("sentiment_label");
        return new ConsoleArticle(
                row.getObject("article_id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getString("source_name"),
                row.getString("title"),
                row.getString("ai_title"),
                row.getString("original_url"),
                row.getString("excerpt"),
                row.getString("ai_summary"),
                sentiment == null ? null : SentimentLabel.valueOf(sentiment),
                instant(row, "published_at"),
                instant(row, "collected_at"),
                NewsArticleStatus.valueOf(row.getString("status")),
                row.getInt("summary_attempts"),
                row.getString("summary_error"),
                instant(row, "summary_attempted_at"),
                instant(row, "hidden_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
