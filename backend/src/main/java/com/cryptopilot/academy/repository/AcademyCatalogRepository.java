package com.cryptopilot.academy.repository;

import com.cryptopilot.academy.model.ArticleCard;
import com.cryptopilot.academy.model.ArticleFilter;
import com.cryptopilot.academy.model.ArticleText;
import com.cryptopilot.academy.model.GlossaryEntry;
import com.cryptopilot.academy.model.TopicEntry;
import com.cryptopilot.academy.model.enums.ArticleLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads what the public side of the Academy shows: topics, published lessons and the glossary. Only
 * {@code PUBLISHED} lessons leave this class; a draft or a hidden lesson is not found, and a term pointing at one is
 * listed without its link.
 *
 * <p>Rule: T-114.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AcademyCatalogRepository {

    private static final String CARD_COLUMNS = """
            select a.slug, a.title, a.summary, a.article_level, a.reading_minutes, a.path_order, a.cover_image_url,
                   t.slug as topic_slug, t.name as topic_name""";

    private static final String PUBLISHED_FROM = """
             from academy_article a
             join academy_topic t on t.topic_id = a.topic_id
            where a.article_status = 'PUBLISHED'""";

    /** Lessons in the order a reader goes through them: topic by topic, then inside the topic. */
    private static final String READING_ORDER = " order by t.sort_order, a.sort_order, a.slug";

    private final JdbcClient sql;

    /** Every topic in display order, with the number of its published lessons. */
    public List<TopicEntry> topics() {
        return sql.sql("""
                        select t.slug, t.name, t.description,
                               (select count(*) from academy_article a
                                 where a.topic_id = t.topic_id and a.article_status = 'PUBLISHED') as articles
                          from academy_topic t
                         order by t.sort_order, t.slug""")
                .query((row, index) -> new TopicEntry(
                        row.getString("slug"),
                        row.getString("name"),
                        row.getString("description"),
                        row.getLong("articles")))
                .list();
    }

    /** One page of published lessons in reading order. */
    public Page<ArticleCard> articles(ArticleFilter filter, Pageable page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(PUBLISHED_FROM);
        if (filter.topic() != null) {
            where.append(" and t.slug = :topic");
            params.put("topic", filter.topic());
        }
        if (filter.level() != null) {
            where.append(" and a.article_level = :level");
            params.put("level", filter.level().name());
        }
        if (filter.text() != null) {
            where.append(" and (a.title ilike :text escape '\\' or a.summary ilike :text escape '\\')");
            params.put("text", "%" + likeEscaped(filter.text()) + "%");
        }
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", page.getPageSize());
        paged.put("offset", page.getOffset());
        List<ArticleCard> cards = sql.sql(CARD_COLUMNS + where + READING_ORDER + " limit :limit offset :offset")
                .params(paged)
                .query((row, index) -> card(row))
                .list();
        long total = sql.sql("select count(*)" + where)
                .params(params)
                .query(Long.class)
                .single();
        return new PageImpl<>(cards, page, total);
    }

    /** The published lessons of one topic, in reading order. */
    public List<ArticleCard> topicArticles(String topicSlug) {
        return sql.sql(CARD_COLUMNS + PUBLISHED_FROM + " and t.slug = :topic" + READING_ORDER)
                .param("topic", topicSlug)
                .query((row, index) -> card(row))
                .list();
    }

    /** The published lessons of the beginner path, in path order. */
    public List<ArticleCard> beginnerPath() {
        return sql.sql(CARD_COLUMNS + PUBLISHED_FROM + " and a.path_order is not null order by a.path_order")
                .query((row, index) -> card(row))
                .list();
    }

    /** The published lesson with this slug, or empty. */
    public Optional<ArticleText> article(String slug) {
        return sql.sql(CARD_COLUMNS + ", a.body, a.app_link, a.published_at, a.updated_at, a.cover_credit"
                        + PUBLISHED_FROM + " and a.slug = :slug")
                .param("slug", slug)
                .query((row, index) -> new ArticleText(
                        card(row),
                        row.getString("body"),
                        row.getString("app_link"),
                        row.getTimestamp("published_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant(),
                        row.getString("cover_credit")))
                .optional();
    }

    /**
     * The glossary in alphabetical order.
     *
     * @param text only terms whose name or definition contains this, or {@code null}
     * @param letter only terms whose name starts with this letter, case ignored, or {@code null}
     */
    public List<GlossaryEntry> terms(String text, String letter) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" where true");
        if (text != null) {
            where.append(" and (g.term ilike :text escape '\\' or g.definition ilike :text escape '\\')");
            params.put("text", "%" + likeEscaped(text) + "%");
        }
        if (letter != null) {
            where.append(" and upper(left(g.term, 1)) = upper(:letter)");
            params.put("letter", letter);
        }
        return sql.sql("""
                        select g.slug, g.term, g.definition, a.slug as article_slug, a.title as article_title
                          from academy_term g
                          left join academy_article a
                            on a.article_id = g.article_id and a.article_status = 'PUBLISHED'""" + where + " order by lower(g.term), g.slug")
                .params(params)
                .query((row, index) -> new GlossaryEntry(
                        row.getString("slug"),
                        row.getString("term"),
                        row.getString("definition"),
                        row.getString("article_slug"),
                        row.getString("article_title")))
                .list();
    }

    private static ArticleCard card(ResultSet row) throws SQLException {
        return new ArticleCard(
                row.getString("slug"),
                row.getString("title"),
                row.getString("summary"),
                ArticleLevel.valueOf(row.getString("article_level")),
                row.getInt("reading_minutes"),
                row.getString("topic_slug"),
                row.getString("topic_name"),
                row.getObject("path_order", Integer.class),
                row.getString("cover_image_url"));
    }

    // A LIKE pattern matches the text literally: its own wildcards and the escape are escaped.
    private static String likeEscaped(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
