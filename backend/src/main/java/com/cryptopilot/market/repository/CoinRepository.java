package com.cryptopilot.market.repository;

import com.cryptopilot.market.entity.Coin;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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

    /** The coins with these symbols; an unknown symbol is left out. */
    @Transactional(readOnly = true)
    List<Coin> findAllBySymbolIn(Collection<String> symbols);

    /** The coins with these keys; an unknown key is left out. */
    @Transactional(readOnly = true)
    List<Coin> findAllByIdIn(Collection<UUID> coinIds);

    /**
     * Stores a coin by its symbol unless one is stored already, named after the symbol as {@code Coin.fromExchange}
     * names it; the symbol synchronisation finds and keeps it later. One statement, so two callers racing on the same
     * symbol both succeed and one row results ({@code uq_coin_symbol}).
     *
     * @return 1 when the coin was inserted, 0 when it existed
     */
    @Modifying
    @Query(
            value = "insert into coin (coin_id, symbol, coin_name, created_at, updated_at)"
                    + " values (:coinId, :symbol, :symbol, :now, :now) on conflict (symbol) do nothing",
            nativeQuery = true)
    int insertIfAbsent(UUID coinId, String symbol, Instant now);

    /** Writes a coin. Not transactional here; the unit of work is the calling service's. */
    Coin save(Coin coin);

    /** Sends pending statements, so a constraint is checked where the caller can read the answer. */
    void flush();
}
