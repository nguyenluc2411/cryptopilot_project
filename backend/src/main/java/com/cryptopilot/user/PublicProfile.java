package com.cryptopilot.user;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What any visitor may see of a member: the public name and avatar and when they joined, and nothing that is not
 * already shown next to their posts (no address, no role, no trading data).
 *
 * <p>Rule: D-99 (CoinMarketCap Community profile); SRS 4.2 (personal data).
 *
 * @param userId the account
 * @param displayName the name of the profile
 * @param avatarUrl the avatar image, or {@code null}
 * @param joinedAt when the account was created
 */
public record PublicProfile(UUID userId, String displayName, String avatarUrl, Instant joinedAt) {

    public PublicProfile {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
