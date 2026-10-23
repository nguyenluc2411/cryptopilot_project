package com.cryptopilot.user.event;

import java.time.Instant;
import java.util.UUID;

/**
 * An account was banned, so every session it holds must end (BR-06). Published inside the transaction that changed
 * the status and handled in it by {@code auth}, which owns the sessions: the ban and the revocation commit together or
 * not at all.
 *
 * <p>Rule: BR-05, BR-06.
 *
 * @param userId the account
 * @param suspendedAt when it was banned
 */
public record AccountSuspended(UUID userId, Instant suspendedAt) {}
