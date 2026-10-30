package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.FuturesSymbolConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The leverage and margin type each Trader chose per Futures pair. Every lookup names the Trader.
 *
 * <p>Rule: TR-14; D-127.
 */
public interface FuturesSymbolConfigRepository extends Repository<FuturesSymbolConfig, UUID> {

    /** The Trader's settings of one pair, or empty when the pair trades at the default. */
    Optional<FuturesSymbolConfig> findByUserIdAndPairId(UUID userId, UUID pairId);

    /** Every pair the Trader configured. */
    List<FuturesSymbolConfig> findByUserId(UUID userId);

    FuturesSymbolConfig save(FuturesSymbolConfig config);
}
