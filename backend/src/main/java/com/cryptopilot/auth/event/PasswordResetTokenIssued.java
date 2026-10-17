package com.cryptopilot.auth.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A password reset link has been created and has to be mailed (SRS UC-04, section 3.2.4).
 *
 * <p>Published inside the transaction that writes the token and delivered after it commits, so a
 * request that rolled back mails nothing and a link that was mailed always has a row behind it.
 *
 * <p>It carries the value itself, not the digest, because the digest is one way and a link cannot be
 * rebuilt from it. That is the same trade {@link VerificationTokenIssued} makes and it carries the
 * same handling: the event publication registry writes the value to
 * {@code event_publication.serialized_event} in the clear, and per D-95 (A-18) the row is deleted as
 * soon as the mail is sent, or after at most 24 hours when it cannot be delivered. That matters more
 * here than for a verification link, because presenting one of these sets a password.
 *
 * <p>{@link #toString()} is overridden for the same reason it is on the other one: a record prints
 * every component, and the whole point of storing a digest is undone by one log line.
 *
 * <p>Rule: BR-04; SRS UC-04.
 */
public record PasswordResetTokenIssued(UUID userId, String email, String token, Instant expiresAt) {

    @Override
    public String toString() {
        return "PasswordResetTokenIssued[userId=" + userId + ", expiresAt=" + expiresAt + "]";
    }
}
