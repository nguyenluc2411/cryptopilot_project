package com.cryptopilot.news.repository;

import com.cryptopilot.news.entity.NewsSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The news sources (UC-49). The table holds the handful of outlets an administrator configures, so the console reads
 * it whole; the crawl reads only the sources that are due.
 *
 * <p>Rule: UC-49; BR-49; NSF-10.
 */
public interface NewsSourceRepository extends Repository<NewsSource, UUID> {

    NewsSource save(NewsSource source);

    void delete(NewsSource source);

    void flush();

    @Transactional(readOnly = true)
    Optional<NewsSource> findById(UUID id);

    /** Every source, by name: the list of SCR-41. Bounded by what administrators configure, not by users. */
    @Transactional(readOnly = true)
    @Query("select s from NewsSource s order by lower(s.sourceName), s.id")
    List<NewsSource> findAllForConsole();

    @Transactional(readOnly = true)
    boolean existsByFeedUrl(String feedUrl);

    @Transactional(readOnly = true)
    boolean existsByFeedUrlAndIdNot(String feedUrl, UUID id);

    /**
     * The active sources whose interval has elapsed at {@code now} (BR-49), never-read ones first, then the longest
     * waiting: what one run of NSF-10 reads. The same rule as {@link NewsSource#isDue(Instant)}, in SQL.
     */
    @Transactional(readOnly = true)
    @Query(value = """
                    select * from news_source
                     where is_active
                       and (last_crawled_at is null
                            or last_crawled_at + make_interval(mins => crawl_interval_minutes) <= :now)
                     order by last_crawled_at nulls first, source_id""", nativeQuery = true)
    List<NewsSource> findDue(@Param("now") Instant now);

    /** Whether any article came from the source; such a source can be switched off but not deleted. */
    @Transactional(readOnly = true)
    @Query(value = "select exists (select 1 from news_article where source_id = :id)", nativeQuery = true)
    boolean hasArticles(@Param("id") UUID id);
}
