package com.cryptopilot.community.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A member's public page, as on CoinMarketCap Community (D-99): who they are and what they did in the community.
 * Their posts are the feed filtered by {@code author}. Nothing of their trading is shown.
 *
 * <p>Rule: D-99.
 *
 * @param userId the member
 * @param displayName their name
 * @param avatarUrl their avatar, or {@code null}
 * @param joinedAt when they joined
 * @param posts their published posts
 * @param likesReceived the likes their published posts received
 * @param me whether this is the caller's own page
 */
public record MemberProfileResponse(
        UUID userId,
        String displayName,
        String avatarUrl,
        Instant joinedAt,
        long posts,
        long likesReceived,
        boolean me) {}
