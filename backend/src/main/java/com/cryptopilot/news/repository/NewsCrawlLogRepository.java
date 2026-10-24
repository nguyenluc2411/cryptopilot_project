package com.cryptopilot.news.repository;

import com.cryptopilot.news.entity.NewsCrawlLog;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The crawl log (NSF-10), written once per crawl of a source and read by SCR-41 (UC-49). Both reads follow
 * {@code idx_news_crawl_log_source (source_id, started_at DESC)}.
 *
 * <p>Rule: NSF-10; UC-49.
 */
public interface NewsCrawlLogRepository extends Repository<NewsCrawlLog, UUID> {

    NewsCrawlLog save(NewsCrawlLog log);

    /** One page of a source's crawls, the latest first. */
    @Transactional(readOnly = true)
    Page<NewsCrawlLog> findBySourceIdOrderByStartedAtDescIdDesc(UUID sourceId, Pageable page);

    /** The latest crawl of each of the sources that has one, in one statement. */
    @Transactional(readOnly = true)
    @Query(value = """
                    select distinct on (source_id) * from news_crawl_log
                     where source_id in (:sources)
                     order by source_id, started_at desc, crawl_log_id desc""", nativeQuery = true)
    List<NewsCrawlLog> findLatestOf(@Param("sources") Collection<UUID> sources);
}
