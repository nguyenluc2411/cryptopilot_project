package com.cryptopilot.community.model;

import java.time.Instant;

/**
 * How much the community talks about one coin: its published posts in the last day, the last week and in all, and
 * when the newest was published (D-99, "coin-related discussions").
 *
 * @param postsLastDay published posts tagged with the coin in the last 24 hours
 * @param postsLastWeek published posts tagged with it in the last 7 days
 * @param totalPosts all its published posts
 * @param lastPostAt when the newest was published, or {@code null} when there is none
 */
public record CoinDiscussion(long postsLastDay, long postsLastWeek, long totalPosts, Instant lastPostAt) {}
