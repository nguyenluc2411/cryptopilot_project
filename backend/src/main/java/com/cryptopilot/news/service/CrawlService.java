package com.cryptopilot.news.service;

import com.cryptopilot.news.model.CrawlRun;

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
}
