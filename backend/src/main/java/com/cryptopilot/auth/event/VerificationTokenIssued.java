package com.cryptopilot.auth.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A verification link has been created and needs to reach its owner.
 *
 * <p>Published after the transaction that created it commits, never inside it: a mail sent for a
 * transaction that then rolls back cannot be recalled, and SRS UC-01 is explicit that a failure to
 * send must leave the account in place rather than undo it. The notification module mails it
 * (T-057) through the event publication registry (T-056).
 *
 * <p>It carries the token itself, which nothing else does — the database holds only a digest. That
 * is unavoidable, because a link cannot be rebuilt from a digest. The registry therefore writes the
 * value to {@code event_publication.serialized_event} in the clear; per D-95 (A-18) the row is
 * deleted as soon as the mail is sent, and one that could not be delivered is deleted after at most
 * 24 hours, the lifetime of this link.
 *
 * <p>Rule: BR-01; SRS UC-01, UC-02.
 *
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 14
 * (a domain event names something that has happened, and is published once it has).
 *
 * @param userId the account the link belongs to
 * @param email where the link is to be sent
 * @param token the value to put in the link; never stored, never logged
 * @param expiresAt when the link stops working (BR-01)
 */
public record VerificationTokenIssued(UUID userId, String email, String token, Instant expiresAt) {

    /** Deliberately says nothing about the token: this is what ends up in a log line. */
    @Override
    public String toString() {
        return "VerificationTokenIssued[userId=" + userId + ", expiresAt=" + expiresAt + "]";
    }
}
