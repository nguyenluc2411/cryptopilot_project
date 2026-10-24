package com.cryptopilot.user.event;

import java.time.Instant;
import java.util.UUID;

/**
 * An account was banned, locked or given another role, so every session it holds must end (BR-06; a role change
 * too, since an access token carries the role it was issued with). Published inside the transaction that changed the
 * account and handled in it by {@code auth}, which owns the sessions: the change and the revocation commit together
 * or not at all.
 *
 * <p>Rule: BR-05, BR-06.
 *
 * @param userId the account
 * @param suspendedAt when it was changed
 */
public record AccountSuspended(UUID userId, Instant suspendedAt) {}
