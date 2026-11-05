package com.cryptopilot.trading;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The names of the trading strategies, for the modules outside {@code trading}, which owns the catalogue.
 *
 * <p>Rule: UC-25; TECHNICAL_DESIGN section 2.
 */
public interface StrategyCatalog {

    /** The name of each of these strategies that exists, by id. */
    Map<UUID, String> names(Collection<UUID> strategyIds);
}
