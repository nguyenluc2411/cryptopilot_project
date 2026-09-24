package com.cryptopilot.market.repository;

import com.cryptopilot.market.entity.Coin;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to coins. A coin is looked up by the symbol the exchange uses for it
 * ({@code uq_coin_symbol}), which is how NSF-01 finds the base and quote of a pair it synchronises.
 *
 * <p>Rule: SRS 3.1.5; NSF-01; D-23.
 */
public interface CoinRepository extends Repository<Coin, UUID> {

    @Transactional(readOnly = true)
    Optional<Coin> findById(UUID coinId);

    /** The coin with this symbol, e.g. {@code BTC}, or empty. */
    @Transactional(readOnly = true)
    Optional<Coin> findBySymbol(String symbol);

    /** Writes a coin. Not transactional here; the unit of work is the calling service's. */
    Coin save(Coin coin);

    /** Sends pending statements, so a constraint is checked where the caller can read the answer. */
    void flush();
}
