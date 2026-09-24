package com.cryptopilot.market.repository;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.entity.CryptoPair;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to pairs.
 *
 * <p>Two read paths and no more, the two the tasks after this one are known to need: a pair by its symbol
 * (NSF-01 matching the exchange's list, the market endpoints of UC-09) and the pairs enabled on one market
 * in display order (NSF-02 and NSF-03 deciding what to collect, SCR-01 and SCR-09 listing them). The
 * symbol lookup is served by {@code uq_crypto_pair_symbol}; the enabled list is not indexed, because the
 * table holds the 30 to 50 pairs an administrator enables (Q-05) and a scan of it is one page.
 *
 * <p>Extends {@code Repository} rather than {@code JpaRepository}, as every repository here does (D-23).
 *
 * <p>Rule: BR-07; NSF-01, NSF-02; TECHNICAL_DESIGN 6.
 */
public interface CryptoPairRepository extends Repository<CryptoPair, UUID> {

    @Transactional(readOnly = true)
    Optional<CryptoPair> findById(UUID pairId);

    /** The pair with this symbol, e.g. {@code BTCUSDT}, or empty. */
    @Transactional(readOnly = true)
    Optional<CryptoPair> findBySymbol(String symbol);

    /** The pairs available on Spot (BR-07), lowest display order first, then by symbol. */
    @Transactional(readOnly = true)
    @Query("select p from CryptoPair p where p.pairStatus = com.cryptopilot.market.entity.PairStatus.ACTIVE"
            + " and p.spotEnabled = true order by p.displayOrder, p.symbol")
    List<CryptoPair> findEnabledOnSpot();

    /** The pairs available on futures (BR-07), lowest display order first, then by symbol. */
    @Transactional(readOnly = true)
    @Query("select p from CryptoPair p where p.pairStatus = com.cryptopilot.market.entity.PairStatus.ACTIVE"
            + " and p.futuresEnabled = true order by p.displayOrder, p.symbol")
    List<CryptoPair> findEnabledOnFutures();

    /** The pairs available on one market (BR-07), in display order. */
    default List<CryptoPair> findEnabledOn(MarketType market) {
        return market == MarketType.SPOT ? findEnabledOnSpot() : findEnabledOnFutures();
    }

    /** Writes a pair. Not transactional here; the unit of work is the calling service's. */
    CryptoPair save(CryptoPair pair);

    /** Sends pending statements, so a constraint is checked where the caller can read the answer. */
    void flush();
}
