package com.cryptopilot.community.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A member started following another (D-125). Published in the transaction that recorded it, for the notification of
 * the member followed, which the in-app notification task delivers.
 *
 * <p>Rule: D-125; NSF-15.
 *
 * @param followerId who follows
 * @param followeeId who is followed, the one to tell
 * @param followedAt when
 */
public record UserFollowed(UUID followerId, UUID followeeId, Instant followedAt) {}
