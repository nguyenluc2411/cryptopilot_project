package com.cryptopilot.community.dto.response;

import java.time.Instant;

/**
 * A coin a member's posts talk about, one row of Token Mentions on the member page (D-99, as on CoinMarketCap
 * Community). The price is the client's, from the live ticker; the lean is the member's own, from the stances of their
 * posts.
 *
 * <p>Rule: D-99, D-125.
 *
 * @param symbol the coin, e.g. {@code BTC}
 * @param name its name, e.g. {@code Bitcoin}
 * @param posts the member's published posts tagged with it
 * @param sharePercent its share of the coin tags of all their published posts, two decimals (mindshare)
 * @param bullishPosts those of the posts the member marked Bullish
 * @param bearishPosts those the member marked Bearish
 * @param lastPostAt when the newest of the posts was published
 */
public record TokenMentionResponse(
        String symbol,
        String name,
        long posts,
        double sharePercent,
        long bullishPosts,
        long bearishPosts,
        Instant lastPostAt) {}
