package com.cryptopilot.trading.service.impl;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-user lock under which a plan activation counts the Trader's ACTIVE plans and open positions and then adds
 * one (D-63). Counting and inserting under READ COMMITTED is a check-then-act on a set of rows: two activations at
 * {@code max − 1} each see room and both commit, and a row lock cannot help because the row that would conflict does
 * not exist yet. A transaction-scoped advisory lock on the user serialises them instead and is released at commit or
 * rollback; it touches no table, so no other module's row is locked.
 *
 * <p>Rule: BR-62; D-63.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley,
 * "Pessimistic Offline Lock".
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew and
 * phantoms).
 * <p>Reference: PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, §13.3.5 "Advisory Locks".
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ActivePlanLock {

    private final JdbcClient jdbc;

    /**
     * Waits for and takes the user's lock until the calling transaction ends. The key is the D-63 key,
     * {@code hashtext(user_id::text)}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID userId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(cast(? as text)))")
                .param(userId)
                .query((row, index) -> Boolean.TRUE)
                .single();
    }
}
