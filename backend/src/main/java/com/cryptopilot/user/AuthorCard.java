package com.cryptopilot.user;

import java.util.Objects;
import java.util.UUID;

/**
 * How an account is shown next to what it wrote: the public name and avatar of its profile, and nothing that is not
 * already shown to every other Trader (no address, no role).
 *
 * <p>Rule: UC-26; SRS 3.7.1.
 *
 * @param userId the account
 * @param displayName the name of the profile
 * @param avatarUrl the avatar image, or {@code null} when none was uploaded
 */
public record AuthorCard(UUID userId, String displayName, String avatarUrl) {

    public AuthorCard {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
    }
}
