package com.cryptopilot.news.repository;

import com.cryptopilot.news.entity.NewsArticle;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The collected articles (NSF-10). An article is written with one statement that does nothing when its normalized
 * address was stored before ({@code uq_news_article_url_hash}), so the same item read twice, by two runs or in one,
 * is one article (BR-48). The same story from another outlet is found on its title hash within a window of publish
 * times, by {@code idx_news_article_title_hash (title_hash, published_at)}.
 *
 * <p>Rule: BR-48; NSF-10.
 */
public interface NewsArticleRepository extends Repository<NewsArticle, UUID> {

    /** Writes an article unless its address hash is stored; 1 when a row was added. */
    @Modifying
    @Query(value = """
                    insert into news_article (article_id, source_id, title, original_url, url_hash, title_hash, excerpt,
                                              published_at, collected_at, created_at, updated_at)
                    values (:id, :source, :title, :url, :urlHash, :titleHash, :excerpt, :publishedAt, :collectedAt,
                            :collectedAt, :collectedAt)
                    on conflict (url_hash) do nothing""", nativeQuery = true)
    int insert(
            @Param("id") UUID id,
            @Param("source") UUID source,
            @Param("title") String title,
            @Param("url") String url,
            @Param("urlHash") String urlHash,
            @Param("titleHash") String titleHash,
            @Param("excerpt") String excerpt,
            @Param("publishedAt") Instant publishedAt,
            @Param("collectedAt") Instant collectedAt);

    /** Records that the article mentions the coin; nothing when it is recorded already. */
    @Modifying
    @Query(value = """
                    insert into news_article_coin (article_id, coin_id, is_primary)
                    values (:article, :coin, :primary)
                    on conflict (article_id, coin_id) do nothing""", nativeQuery = true)
    int tagCoin(@Param("article") UUID article, @Param("coin") UUID coin, @Param("primary") boolean primary);

    /** Whether an article with this title hash was published between {@code from} and {@code to}, both included. */
    @Transactional(readOnly = true)
    @Query(value = """
                    select exists (select 1 from news_article
                                    where title_hash = :titleHash and published_at between :from and :to)""", nativeQuery = true)
    boolean existsTitleBetween(
            @Param("titleHash") String titleHash, @Param("from") Instant from, @Param("to") Instant to);

    @Transactional(readOnly = true)
    Optional<NewsArticle> findByUrlHash(String urlHash);

    /**
     * The newest articles collected since {@code since} that have no summary yet, are shown and were tried fewer than
     * {@code maxAttempts} times, at most {@code limit}: one run of NSF-11 (D-128). Read through
     * {@code idx_news_article_unsummarized}.
     */
    @Transactional(readOnly = true)
    @Query(value = """
                    select * from news_article
                     where ai_summary is null and collected_at >= :since and hidden_at is null
                       and summary_attempts < :maxAttempts
                     order by published_at desc, article_id
                     limit :limit""", nativeQuery = true)
    List<NewsArticle> findUnsummarized(
            @Param("since") Instant since, @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    @Transactional(readOnly = true)
    Optional<NewsArticle> findById(UUID id);

    /** Records an attempt that kept no summary, and why (D-128). */
    @Modifying
    @Query(value = """
                    update news_article
                       set summary_attempts = summary_attempts + 1, summary_error = :error,
                           summary_attempted_at = :now, updated_at = :now, version = version + 1
                     where article_id = :id""", nativeQuery = true)
    int recordFailedAttempt(@Param("id") UUID id, @Param("error") String error, @Param("now") Instant now);

    /**
     * Replaces the translated headline, the summary and the sentiment of an article, summarized or not: an
     * administrator asked for it again (T-080b).
     */
    @Modifying
    @Query(value = """
                    update news_article
                       set ai_title = :title, ai_summary = :summary, sentiment_label = :sentiment,
                           summary_attempts = summary_attempts + 1, summary_error = null,
                           summary_attempted_at = :now, updated_at = :now, version = version + 1
                     where article_id = :id""", nativeQuery = true)
    int replaceSummary(
            @Param("id") UUID id,
            @Param("title") String title,
            @Param("summary") String summary,
            @Param("sentiment") String sentiment,
            @Param("now") Instant now);

    /** Hides a shown article; 0 when it is hidden already. */
    @Modifying
    @Query(value = """
                    update news_article set hidden_at = :now, hidden_by = :admin, updated_at = :now,
                                            version = version + 1
                     where article_id = :id and hidden_at is null""", nativeQuery = true)
    int hide(@Param("id") UUID id, @Param("admin") UUID admin, @Param("now") Instant now);

    /** Shows a hidden article again; 0 when it is shown already. */
    @Modifying
    @Query(value = """
                    update news_article set hidden_at = null, hidden_by = null, updated_at = :now,
                                            version = version + 1
                     where article_id = :id and hidden_at is not null""", nativeQuery = true)
    int restore(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Stores the translated headline, the summary and the sentiment of an article that has no summary yet; 0 when it
     * has one already, so two runs that summarize the same article keep the first answer.
     */
    @Modifying
    @Query(value = """
                    update news_article
                       set ai_title = :title, ai_summary = :summary, sentiment_label = :sentiment,
                           summary_attempts = summary_attempts + 1, summary_error = null,
                           summary_attempted_at = :now, updated_at = :now, version = version + 1
                     where article_id = :id and ai_summary is null""", nativeQuery = true)
    int saveSummary(
            @Param("id") UUID id,
            @Param("title") String title,
            @Param("summary") String summary,
            @Param("sentiment") String sentiment,
            @Param("now") Instant now);
}
