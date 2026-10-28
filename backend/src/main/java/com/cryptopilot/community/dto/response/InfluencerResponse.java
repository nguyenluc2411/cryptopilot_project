package com.cryptopilot.community.dto.response;

import java.util.UUID;

/**
 * One row of the Top Influencers list of SCR-24 (D-125).
 *
 * @param userId the member
 * @param displayName their name, or {@code null} for an account without a profile
 * @param avatarUrl their avatar, or {@code null}
 * @param influenceScore the influence score, see {@link com.cryptopilot.community.model.AuthorStanding}
 * @param depthScore the analytical depth score
 * @param followers the members who follow them
 * @param bullishPosts their posts of the last 30 days marked Bullish
 * @param bearishPosts their posts of the last 30 days marked Bearish
 * @param followedByMe whether the caller follows them
 * @param me whether this is the caller
 */
public record InfluencerResponse(
        UUID userId,
        String displayName,
        String avatarUrl,
        double influenceScore,
        double depthScore,
        long followers,
        long bullishPosts,
        long bearishPosts,
        boolean followedByMe,
        boolean me) {}
