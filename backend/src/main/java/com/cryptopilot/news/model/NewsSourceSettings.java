package com.cryptopilot.news.model;

import java.util.Objects;

/**
 * Everything an administrator sets on a news source (SCR-41), already checked: the name and the feed address present,
 * the address normalized, the interval at least fifteen minutes (BR-49) and the selectors parseable. The optional
 * values are {@code null} rather than blank.
 *
 * <p>Rule: UC-49; BR-49.
 *
 * @param sourceName the name shown in the console and on each article
 * @param feedUrl the normalized address of the feed
 * @param crawlIntervalMinutes how often the feed is read, in minutes, at least 15
 * @param active whether the crawl reads the source
 * @param categoryFilter the feed categories an item must carry to be kept, comma separated, or {@code null}
 * @param bodySelector the CSS selectors of the article body, comma separated, the first that matches wins, or
 *     {@code null}
 * @param excludedSelectors the CSS selectors removed from the body, or {@code null}
 * @param userAgent the User-Agent sent to the outlet, or {@code null} for the configured default
 */
public record NewsSourceSettings(
        String sourceName,
        String feedUrl,
        int crawlIntervalMinutes,
        boolean active,
        String categoryFilter,
        String bodySelector,
        String excludedSelectors,
        String userAgent) {

    /** The shortest interval a source may be read at (BR-49), the bound of {@code ck_news_source_crawl_interval}. */
    public static final int MIN_CRAWL_INTERVAL_MINUTES = 15;

    public NewsSourceSettings {
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        Objects.requireNonNull(feedUrl, "feedUrl must not be null");
        if (crawlIntervalMinutes < MIN_CRAWL_INTERVAL_MINUTES) {
            throw new IllegalArgumentException("the crawl interval is at least " + MIN_CRAWL_INTERVAL_MINUTES
                    + " minutes (BR-49), was " + crawlIntervalMinutes);
        }
    }
}
