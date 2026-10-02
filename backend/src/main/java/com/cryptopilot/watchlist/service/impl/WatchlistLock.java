package com.cryptopilot.watchlist.service.impl;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-user lock under which an add counts the Trader's watchlist rows and inserts one (D-63). Without it two adds
 * at {@code max − 1} each count room and both commit: the row that would conflict does not exist yet, so no row lock
 * can stop them. The transaction-scoped advisory lock serialises the Trader's adds and is released at commit or
 * rollback; it locks no table, so no other module's row is touched.
 *
 * <p>Rule: BR-15, BR-62; D-63.
 *
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (write skew and
 * phantoms). PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, §13.3.5 "Advisory Locks".
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class WatchlistLock {

    private final JdbcClient jdbc;

    /** Waits for and takes the user's lock until the calling transaction ends; key {@code hashtext(user_id::text)}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID userId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(cast(? as text)))")
                .param(userId)
                .query((row, index) -> Boolean.TRUE)
                .single();
    }
}
