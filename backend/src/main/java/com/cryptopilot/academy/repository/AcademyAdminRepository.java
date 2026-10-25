package com.cryptopilot.academy.repository;

import com.cryptopilot.academy.model.ArticleListing;
import com.cryptopilot.academy.model.TermListing;
import com.cryptopilot.academy.model.enums.ArticleLevel;
import com.cryptopilot.academy.model.enums.ArticleStatus;
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
 * Reads what the administration console of the Academy lists: every lesson whatever its status, the glossary, and
 * the topics by slug.
 *
 * <p>Rule: T-114.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AcademyAdminRepository {

    private final JdbcClient sql;

    /**
     * One page of lessons, the latest change first.
     *
     * @param status only lessons in this status, or {@code null}
     * @param topic only lessons of the topic with this slug, or {@code null}
     * @param text only lessons whose title or slug contains this, or {@code null}
     */
    public Page<ArticleListing> articles(ArticleStatus status, String topic, String text, Pageable page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder from =
                new StringBuilder(" from academy_article a join academy_topic t on t.topic_id = a.topic_id where true");
        if (status != null) {
            from.append(" and a.article_status = :status");
            params.put("status", status.name());
        }
        if (topic != null) {
            from.append(" and t.slug = :topic");
            params.put("topic", topic);
        }
        if (text != null) {
            from.append(" and (a.title ilike :text escape '\\' or a.slug ilike :text escape '\\')");
            params.put("text", "%" + likeEscaped(text) + "%");
        }
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", page.getPageSize());
        paged.put("offset", page.getOffset());
        List<ArticleListing> items = sql.sql(
                        """
                        select a.article_id, a.slug, a.title, t.slug as topic_slug, t.name as topic_name,
                               a.article_level, a.article_status, a.sort_order, a.path_order, a.published_at,
                               a.updated_at""" + from + " order by a.updated_at desc, a.slug limit :limit offset :offset")
                .params(paged)
                .query((row, index) -> new ArticleListing(
                        row.getObject("article_id", UUID.class),
                        row.getString("slug"),
                        row.getString("title"),
                        row.getString("topic_slug"),
                        row.getString("topic_name"),
                        ArticleLevel.valueOf(row.getString("article_level")),
                        ArticleStatus.valueOf(row.getString("article_status")),
                        row.getInt("sort_order"),
                        row.getObject("path_order", Integer.class),
                        instantOrNull(row.getTimestamp("published_at")),
                        row.getTimestamp("updated_at").toInstant()))
                .list();
        long total = sql.sql("select count(*)" + from)
                .params(params)
                .query(Long.class)
                .single();
        return new PageImpl<>(items, page, total);
    }

    /** The glossary in alphabetical order, optionally only the terms whose name contains this text. */
    public List<TermListing> terms(String text) {
        Map<String, Object> params = new HashMap<>();
        String where = "";
        if (text != null) {
            where = " where g.term ilike :text escape '\\'";
            params.put("text", "%" + likeEscaped(text) + "%");
        }
        return sql.sql("""
                        select g.term_id, g.slug, g.term, g.definition, a.slug as article_slug
                          from academy_term g
                          left join academy_article a on a.article_id = g.article_id""" + where + " order by lower(g.term), g.slug")
                .params(params)
                .query((row, index) -> new TermListing(
                        row.getObject("term_id", UUID.class),
                        row.getString("slug"),
                        row.getString("term"),
                        row.getString("definition"),
                        row.getString("article_slug")))
                .list();
    }

    /** The key of the topic with this slug, or empty. */
    public Optional<UUID> topicId(String slug) {
        return sql.sql("select topic_id from academy_topic where slug = :slug")
                .param("slug", slug)
                .query(UUID.class)
                .optional();
    }

    /** The slug of the topic with this key. */
    public String topicSlug(UUID topicId) {
        return sql.sql("select slug from academy_topic where topic_id = :id")
                .param("id", topicId)
                .query(String.class)
                .single();
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    // A LIKE pattern matches the text literally: its own wildcards and the escape are escaped.
    private static String likeEscaped(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
