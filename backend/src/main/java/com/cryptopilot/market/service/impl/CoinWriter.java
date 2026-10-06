package com.cryptopilot.market.service.impl;

import com.cryptopilot.common.util.UuidV7;
import com.cryptopilot.market.repository.CoinRepository;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores a coin by its symbol, each in a transaction of its own that commits at once.
 *
 * <p>Two writers store coins: the symbol synchronisation (NSF-01) and the opening of a paper account (TR-04). An insert
 * into {@code coin} holds the lock on its symbol in {@code uq_coin_symbol} until its transaction ends, and a second
 * insert of the same symbol waits for it. Were the coins inserted inside the callers' transactions, one caller holding
 * BTC and waiting for ETH while the other holds ETH and waits for BTC would deadlock, and PostgreSQL would abort one of
 * them: a failed opening, or a whole synchronisation rolled back. Committing every coin by itself means no caller ever
 * holds one coin while it waits for another, so no cycle can form; a caller waits at most for one short insert.
 *
 * <p>A coin is reference data named by the exchange, so committing it even when the caller's own transaction rolls back
 * later is harmless: the next caller finds it and uses it.
 *
 * <p>Rule: NSF-01; TR-04.
 *
 * <p>Reference: PostgreSQL Global Development Group. <i>PostgreSQL 16 Documentation</i>, §13.3.4 "Deadlocks" and
 * INSERT "ON CONFLICT" (a conflicting insert waits for the transaction that inserted the key).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class CoinWriter {

    private final CoinRepository coins;
    private final Clock clock;

    /** Stores the coin unless it is stored already, and commits before returning. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storeIfAbsent(String symbol) {
        coins.insertIfAbsent(UuidV7.next(), symbol, clock.instant());
    }
}
