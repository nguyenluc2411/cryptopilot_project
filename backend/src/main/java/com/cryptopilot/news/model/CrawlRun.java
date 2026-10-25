package com.cryptopilot.news.model;

/**
 * What one run of the crawl did, for its log line.
 *
 * @param sources the sources read, successfully or not
 * @param failed the sources whose feed could not be read
 * @param inserted the articles stored, over all sources
 */
public record CrawlRun(int sources, int failed, int inserted) {

    public static final CrawlRun NOTHING_DUE = new CrawlRun(0, 0, 0);
}
