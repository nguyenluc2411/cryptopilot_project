package com.cryptopilot.user;

import java.util.UUID;

/**
 * What a sign-in needs in order to check a password, and nothing more.
 *
 * <p>Two fields, and the absence of the rest is the point. There is no status, no verification
 * instant and no lockout state on it, because a caller that could read those before offering a
 * password could learn them without knowing one — and the messages SRS 3.2.3 assigns are careful
 * about exactly that. Those answers come back from {@code recordLoginAttempt}, after the comparison.
 *
 * <p>The hash is a credential, so it stays inside the application: it is compared and dropped. It is
 * never logged, never returned in a response and never put in an exception message — which is why
 * this record overrides {@link #toString()}, since the compiler-generated one would print it into
 * the first log line that ever interpolates the record.
 *
 * <p>Rule: BR-02; SRS 3.2.3, 4.2.4; TECHNICAL_DESIGN section 2.
 *
 * @param userId the key of the account holding the address
 * @param passwordHash the stored hash, as the configured encoder produced it at registration
 */
public record LoginCredentials(UUID userId, String passwordHash) {

    /** Names the account and says nothing about the hash. */
    @Override
    public String toString() {
        return "LoginCredentials(" + userId + ")";
    }
}
