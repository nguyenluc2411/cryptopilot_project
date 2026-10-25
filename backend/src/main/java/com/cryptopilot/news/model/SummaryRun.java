package com.cryptopilot.news.model;

/**
 * What one run of NSF-11 did, for its log line.
 *
 * @param due the articles the run took up
 * @param summarized the articles that now have a summary
 * @param failed the articles left without one, tried again at the next run
 */
public record SummaryRun(int due, int summarized, int failed) {

    public static final SummaryRun NOTHING_DUE = new SummaryRun(0, 0, 0);
}
