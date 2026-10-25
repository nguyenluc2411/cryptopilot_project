package com.cryptopilot.news.repository;

import com.cryptopilot.news.model.CoinTag;
import com.cryptopilot.news.model.FeedArticle;
import com.cryptopilot.news.model.FeedArticlePage;
import com.cryptopilot.news.model.NewsFeedQuery;
import com.cryptopilot.news.model.enums.SentimentLabel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the news feed (SCR-28). A page is one query in feed order through {@code idx_news_article_feed}, newest first,
 * with the source's name joined, plus one count; the coin tags of a page are one more query, never one per article.
 *
 * <p>An article an administrator hid is in neither the feed nor the summary bar (T-080b).
 *
 * <p>Rule: UC-34; SRS 3.8.1; CR-04.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class NewsFeedRepository {

    private final JdbcClient sql;

    /** One page of the articles the query asks for, newest first. */
    public FeedArticlePage feed(NewsFeedQuery query, Pageable page) {
        Map<String, Object> params = new HashMap<>();
        params.put("since", Timestamp.from(query.since()));
        StringBuilder where = new StringBuilder(" where a.published_at >= :since and a.hidden_at is null");
        if (query.coinId() != null) {
            where.append(" and exists (select 1 from news_article_coin c"
                    + " where c.article_id = a.article_id and c.coin_id = :coin)");
            params.put("coin", query.coinId());
        }
        if (query.sentiment() != null) {
            where.append(" and a.sentiment_label = :sentiment");
            params.put("sentiment", query.sentiment().name());
        }
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", page.getPageSize());
        paged.put("offset", page.getOffset());
        List<FeedArticle> articles = sql.sql(
                        """
                        select a.article_id, a.title, a.ai_title, s.source_name, a.original_url, a.excerpt,
                               a.ai_summary, a.sentiment_label, a.published_at, a.image_url, null as ai_body
                          from news_article a
                          join news_source s on s.source_id = a.source_id""" + where + " order by a.published_at desc, a.article_id desc limit :limit offset :offset")
                .params(paged)
                .query((row, index) -> article(row))
                .list();
        long total = sql.sql("select count(*) from news_article a" + where)
                .params(params)
                .query(Long.class)
                .single();
        return new FeedArticlePage(articles, total);
    }

    /** One shown article with the article written again (D-129), or empty when there is none or it is hidden. */
    public Optional<FeedArticle> article(UUID articleId) {
        return sql.sql("""
                        select a.article_id, a.title, a.ai_title, s.source_name, a.original_url, a.excerpt,
                               a.ai_summary, a.sentiment_label, a.published_at, a.image_url, a.ai_body
                          from news_article a
                          join news_source s on s.source_id = a.source_id
                         where a.article_id = :id and a.hidden_at is null""")
                .param("id", articleId)
                .query((row, index) -> article(row))
                .optional();
    }

    /** The coins each of these articles is tagged with, the primary one first; an article without tags is left out. */
    public Map<UUID, List<CoinTag>> coinTags(Collection<UUID> articleIds) {
        Map<UUID, List<CoinTag>> result = new HashMap<>();
        if (articleIds.isEmpty()) {
            return result;
        }
        sql.sql("""
                        select article_id, coin_id, is_primary from news_article_coin
                         where article_id in (:articles)
                         order by article_id, is_primary desc, coin_id""")
                .param("articles", articleIds)
                .query((row, index) -> result.computeIfAbsent(
                                row.getObject("article_id", UUID.class), key -> new ArrayList<>())
                        .add(new CoinTag(row.getObject("coin_id", UUID.class), row.getBoolean("is_primary"))))
                .list();
        return result;
    }

    /** How many articles published at or after {@code since} carry each sentiment; a sentiment none carries is 0. */
    public Map<SentimentLabel, Long> sentimentCounts(Instant since) {
        Map<SentimentLabel, Long> result = new EnumMap<>(SentimentLabel.class);
        for (SentimentLabel label : SentimentLabel.values()) {
            result.put(label, 0L);
        }
        sql.sql("""
                        select sentiment_label, count(*) as articles from news_article
                         where published_at >= :since and sentiment_label is not null and hidden_at is null
                         group by sentiment_label""")
                .param("since", Timestamp.from(since))
                .query((row, index) ->
                        result.put(SentimentLabel.valueOf(row.getString("sentiment_label")), row.getLong("articles")))
                .list();
        return result;
    }

    private static FeedArticle article(ResultSet row) throws SQLException {
        String sentiment = row.getString("sentiment_label");
        return new FeedArticle(
                row.getObject("article_id", UUID.class),
                row.getString("title"),
                row.getString("ai_title"),
                row.getString("source_name"),
                row.getString("original_url"),
                row.getString("excerpt"),
                row.getString("ai_summary"),
                sentiment == null ? null : SentimentLabel.valueOf(sentiment),
                row.getTimestamp("published_at").toInstant(),
                row.getString("image_url"),
                row.getString("ai_body"));
    }
}
