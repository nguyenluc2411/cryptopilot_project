package com.cryptopilot.trading;

import java.util.Collection;
import java.util.Map;
import java.util.SequencedMap;
import java.util.UUID;

/**
 * The names of the trading strategies, for the modules outside {@code trading}, which owns the catalogue, and for the
 * list a Trader chooses from.
 *
 * <p>Rule: SRS 3.6.1; UC-22, UC-23, UC-25; TECHNICAL_DESIGN section 2.
 */
public interface StrategyCatalog {

    /** The name of each of these strategies that exists, by id. */
    Map<UUID, String> names(Collection<UUID> strategyIds);

    /** The name of every strategy by id, in the order of the names. */
    SequencedMap<UUID, String> all();
}
