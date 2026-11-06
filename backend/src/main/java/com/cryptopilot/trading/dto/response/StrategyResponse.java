package com.cryptopilot.trading.dto.response;

import java.util.UUID;

/**
 * A strategy a Trader can choose.
 *
 * <p>Rule: SRS 3.6.1; UC-22, UC-23.
 *
 * @param id the strategy
 * @param name its name
 */
public record StrategyResponse(UUID id, String name) {}
