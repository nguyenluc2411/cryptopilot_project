package com.cryptopilot.news.model.enums;

/**
 * How one crawl of one source ended, stored by name in {@code news_crawl_log.crawl_status}
 * ({@code ck_news_crawl_log_status}).
 *
 * <p>Rule: NSF-10 (each run writes the crawl log); UC-49 (the log shows the status).
 */
public enum CrawlStatus {
    /** The feed was read and every item was handled. */
    SUCCESS,
    /** The feed was read but some items could not be handled; the error names them. */
    PARTIAL,
    /** The feed could not be read at all. */
    FAILED
}
