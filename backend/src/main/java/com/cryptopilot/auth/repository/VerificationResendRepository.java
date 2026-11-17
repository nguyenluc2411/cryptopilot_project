package com.cryptopilot.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The accepted verification resends of each address, keyed by the hash of the address (BR-65, T-105).
 *
 * <p>Rule: BR-65; UC-02; D-57, A-36.
 */
@Repository
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class VerificationResendRepository {

    /** Namespace of the advisory lock, so it cannot collide with another lock taken on the same text. */
    private static final String LOCK_PREFIX = "verification-resend:";

    private final JdbcClient sql;

    /** Serialises the resends of one address until the transaction ends; needs an open transaction. */
    public void lock(String addressHash) {
        sql.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", LOCK_PREFIX + addressHash)
                .query((row, index) -> 1)
                .single();
    }

    /** The accepted resends of the address after {@code since} (exclusive), oldest first; the registration mail not. */
    public List<Instant> requestedAfter(String addressHash, Instant since) {
        return sql.sql("""
                        select requested_at from verification_resend_attempt
                         where address_hash = :hash and requested_at > :since and counts_toward_limit
                         order by requested_at""")
                .param("hash", addressHash)
                .param("since", Timestamp.from(since))
                .query((row, index) -> row.getTimestamp("requested_at").toInstant())
                .list();
    }

    /** When the address was last sent a verification mail, the registration mail included, if within the sweep. */
    public Optional<Instant> lastSentAt(String addressHash) {
        return sql.sql("select max(requested_at) from verification_resend_attempt where address_hash = :hash")
                .param("hash", addressHash)
                .query((row, index) -> Optional.ofNullable(row.getTimestamp(1)).map(Timestamp::toInstant))
                .single();
    }

    /** Records one accepted resend. */
    public void record(UUID attemptId, String addressHash, Instant requestedAt) {
        insert(attemptId, addressHash, requestedAt, true);
    }

    /** Records the registration mail: it starts the cooldown but is not one of BR-65's five resends. */
    public void recordRegistration(UUID attemptId, String addressHash, Instant requestedAt) {
        insert(attemptId, addressHash, requestedAt, false);
    }

    private void insert(UUID attemptId, String addressHash, Instant requestedAt, boolean countsTowardLimit) {
        sql.sql("""
                        insert into verification_resend_attempt (attempt_id, address_hash, requested_at,
                                                                 counts_toward_limit)
                        values (:id, :hash, :at, :counts)""")
                .param("id", attemptId)
                .param("hash", addressHash)
                .param("at", Timestamp.from(requestedAt))
                .param("counts", countsTowardLimit)
                .update();
    }

    /** Deletes the rows at or before {@code cutoff}; answers how many. */
    public int deleteRequestedUpTo(Instant cutoff) {
        return sql.sql("delete from verification_resend_attempt where requested_at <= :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
