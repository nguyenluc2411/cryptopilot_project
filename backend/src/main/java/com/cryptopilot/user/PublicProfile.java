package com.cryptopilot.user;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What any visitor may see of a member: the public name and avatar, when they joined, and what they chose to say of
 * themselves on their member page; nothing else (no address, no role, no trading data).
 *
 * <p>Rule: D-99 (CoinMarketCap Community profile); SRS 4.2 (personal data).
 *
 * @param userId the account
 * @param displayName the name of the profile
 * @param avatarUrl the avatar image, or {@code null}
 * @param joinedAt when the account was created
 * @param bio the introduction the member wrote, or {@code null}
 * @param websiteUrl the link the member gave, or {@code null}
 * @param bannerUrl the cover image of the member page, or {@code null} for the default
 */
public record PublicProfile(
        UUID userId,
        String displayName,
        String avatarUrl,
        Instant joinedAt,
        String bio,
        String websiteUrl,
        String bannerUrl) {

    public PublicProfile {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
