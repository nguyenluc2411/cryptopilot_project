package com.cryptopilot.news.model.enums;

/**
 * Where an article stands for the administration console (T-080b), worked out from its row rather than stored, so it
 * can never disagree with it. A hidden article is {@code HIDDEN} whatever its summary.
 *
 * <p>Rule: NSF-11; D-128.
 */
public enum NewsArticleStatus {
    /** No summary yet; NSF-11 takes it up at a coming run. */
    PENDING,
    /** No summary after the configured number of attempts: it waits for an administrator to ask again (D-128). */
    FAILED,
    /** Has its AI summary. */
    SUMMARIZED,
    /** Hidden from the feed by an administrator. */
    HIDDEN
}
