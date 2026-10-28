package com.cryptopilot.user.event;

import java.time.Instant;
import java.util.UUID;

/**
 * An account was banned, by an administrator (UC-42) or by a moderation decision (UC-43). A ban is final (BR-05), so
 * the community takes the member's posts and comments out of sight and drops their follows and blocks (D-125).
 * Published inside the transaction that banned the account, beside {@link AccountSuspended}.
 *
 * <p>Rule: BR-05, BR-06; D-125.
 *
 * @param userId the account
 * @param bannedAt when it was banned
 */
public record AccountBanned(UUID userId, Instant bannedAt) {}
