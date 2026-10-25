package com.cryptopilot.academy.repository;

import com.cryptopilot.academy.model.CompletedLesson;
import com.cryptopilot.academy.model.LessonTotals;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The lessons each Trader marked as learned. Marking is one statement that the unique key
 * {@code uq_academy_progress_user_article} makes idempotent, so marking twice keeps the first instant. Only published
 * lessons are marked or counted: a lesson hidden later stays in the table and comes back with it.
 *
 * <p>Rule: T-114.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AcademyProgressRepository {

    private final JdbcClient sql;

    /** The key of the published lesson with this slug, or empty. */
    public Optional<UUID> publishedArticleId(String slug) {
        return sql.sql("select article_id from academy_article where slug = :slug and article_status = 'PUBLISHED'")
                .param("slug", slug)
                .query(UUID.class)
                .optional();
    }

    /** Marks a lesson as learned at {@code now}, unless it already is; answers when it was marked. */
    public Instant mark(UUID progressId, UUID userId, UUID articleId, Instant now) {
        Timestamp at = Timestamp.from(now);
        sql.sql("""
                        insert into academy_progress (progress_id, user_id, article_id, created_at, updated_at)
                        values (:id, :user, :article, :at, :at)
                        on conflict (user_id, article_id) do nothing""")
                .param("id", progressId)
                .param("user", userId)
                .param("article", articleId)
                .param("at", at)
                .update();
        return sql.sql("select created_at from academy_progress where user_id = :user and article_id = :article")
                .param("user", userId)
                .param("article", articleId)
                .query(Timestamp.class)
                .single()
                .toInstant();
    }

    /** Takes the mark off a lesson; nothing happens when it had none. */
    public void unmark(UUID userId, UUID articleId) {
        sql.sql("delete from academy_progress where user_id = :user and article_id = :article")
                .param("user", userId)
                .param("article", articleId)
                .update();
    }

    /** The published lessons the Trader marked, oldest mark first. */
    public List<CompletedLesson> completed(UUID userId) {
        return sql.sql("""
                        select a.slug, a.path_order is not null as on_path, p.created_at
                          from academy_progress p
                          join academy_article a on a.article_id = p.article_id
                         where p.user_id = :user and a.article_status = 'PUBLISHED'
                         order by p.created_at, a.slug""")
                .param("user", userId)
                .query((row, index) -> new CompletedLesson(
                        row.getString("slug"),
                        row.getBoolean("on_path"),
                        row.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** How many lessons are published, and how many of them are on the beginner path. */
    public LessonTotals publishedTotals() {
        return sql.sql("""
                        select count(*) as total, count(*) filter (where path_order is not null) as on_path
                          from academy_article
                         where article_status = 'PUBLISHED'""")
                .query((row, index) -> new LessonTotals(row.getLong("total"), row.getLong("on_path")))
                .single();
    }
}
