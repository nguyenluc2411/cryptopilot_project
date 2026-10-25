package com.cryptopilot.news.service;

import com.cryptopilot.news.entity.NewsCrawlLog;
import com.cryptopilot.news.model.CrawlRun;
import java.util.UUID;

/**
 * NSF-10: reads the feed of every source that is due, stores the new articles, and writes the crawl log. Implemented by
 * {@link com.cryptopilot.news.service.impl.CrawlServiceImpl}.
 *
 * <p>Rule: NSF-10; BR-48, BR-49.
 */
public interface CrawlService {

    /**
     * One run: every active source whose interval has elapsed (BR-49), one after the other. A source whose feed cannot
     * be read is logged FAILED and does not stop the others.
     */
    CrawlRun crawlDue();

    /**
     * Crawls one source now, active or not, an administrator having asked (T-080b). The source then waits one interval
     * from now, like after any crawl (BR-49).
     *
     * @return the crawl log entry written
     * @throws com.cryptopilot.common.exception.ResourceNotFoundException when there is no such source
     * @throws com.cryptopilot.common.exception.BusinessException {@code NEWS_SOURCE_CRAWLED_RECENTLY} within the
     *     cooldown of its last crawl, with the seconds left
     */
    NewsCrawlLog crawlNow(UUID sourceId);
}
