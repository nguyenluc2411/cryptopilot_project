package com.cryptopilot.community.dto.response;

import java.time.Instant;

/**
 * The discussion block of a coin's page (SCR-10, SCR-11): how much the community talks about the coin. The posts
 * themselves are the feed filtered by the coin. Public, like the feed (D-99).
 *
 * <p>Rule: UC-26; D-99.
 *
 * @param symbol the coin, e.g. {@code BTC}
 * @param name its name
 * @param postsLastDay published posts tagged with it in the last 24 hours
 * @param postsLastWeek published posts tagged with it in the last 7 days
 * @param totalPosts all its published posts
 * @param lastPostAt when the newest was published, or {@code null}
 */
public record CoinDiscussionResponse(
        String symbol, String name, long postsLastDay, long postsLastWeek, long totalPosts, Instant lastPostAt) {}
