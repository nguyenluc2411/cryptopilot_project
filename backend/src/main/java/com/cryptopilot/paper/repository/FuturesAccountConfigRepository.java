package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.FuturesAccountConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The Futures settings of the Traders, one row each, keyed by the Trader.
 *
 * <p>Rule: TR-14; D-127.
 */
public interface FuturesAccountConfigRepository extends Repository<FuturesAccountConfig, UUID> {

    /** The Trader's settings, or empty before the first Futures action. */
    Optional<FuturesAccountConfig> findById(UUID userId);

    FuturesAccountConfig save(FuturesAccountConfig config);

    /** The Traders whose cross liquidation started at or before {@code at} and still runs (TR-23). */
    List<FuturesAccountConfig> findByLiquidatingSinceLessThanEqual(Instant at);
}
