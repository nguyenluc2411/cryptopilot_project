package com.cryptopilot.trading.service.impl;

import com.cryptopilot.trading.StrategyCatalog;
import com.cryptopilot.trading.repository.StrategyNameRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Serves {@link StrategyCatalog} from the strategy table.
 *
 * <p>Rule: UC-25; TECHNICAL_DESIGN section 2.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class StrategyCatalogImpl implements StrategyCatalog {

    private final StrategyNameRepository strategies;

    @Override
    public Map<UUID, String> names(Collection<UUID> strategyIds) {
        return strategies.names(strategyIds);
    }
}
