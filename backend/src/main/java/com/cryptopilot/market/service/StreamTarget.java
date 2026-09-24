package com.cryptopilot.market.service;

import java.util.UUID;

/**
 * A pair NSF-03 streams on one market.
 *
 * <p>Rule: NSF-03; BR-07.
 *
 * @param pairId the pair
 * @param symbol its symbol as the exchange spells it, e.g. {@code BTCUSDT}
 */
public record StreamTarget(UUID pairId, String symbol) {}
