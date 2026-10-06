package com.cryptopilot.common.lock;

import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-user locks under which a module counts a user's rows and then adds one, or changes several rows that must
 * not interleave with another change of the same user (D-63). Counting and inserting under READ COMMITTED is a
 * check-then-act on a set of rows: two requests at {@code max − 1} each see room and both commit, and a row lock
 * cannot help because the row that would conflict does not exist yet. A transaction-scoped advisory lock serialises
 * them instead and is released at commit or rollback; it touches no table, so no module's row is locked.
 *
 * <p>Two kinds of key:
 *
 * <ul>
 *   <li>{@link #lock(UUID)}: the D-63 key {@code hashtext(user_id::text)}, shared by the plan activations and the
 *       watchlist and alert writes as it always was.
 *   <li>{@link #lock(String, UUID)}: a key of its own per scope, {@code (hashtext(scope), hashtext(user_id::text))},
 *       for a module whose changes need not wait for the others' (paper trading).
 * </ul>
 *
 * <p>Rule: BR-15, BR-62; D-63; TR-04.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew and
 * phantoms). PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, §13.3.5 "Advisory Locks".
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class UserLock {

    private final JdbcClient jdbc;

    /** Waits for and takes the user's D-63 lock until the calling transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        jdbc.sql("select pg_advisory_xact_lock(hashtext(cast(? as text)))")
                .param(userId)
                .query((row, index) -> Boolean.TRUE)
                .single();
    }

    /** Waits for and takes the user's lock of one scope until the calling transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String scope, UUID userId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(userId, "userId");
        jdbc.sql("select pg_advisory_xact_lock(hashtext(?), hashtext(cast(? as text)))")
                .params(scope, userId)
                .query((row, index) -> Boolean.TRUE)
                .single();
    }
}
