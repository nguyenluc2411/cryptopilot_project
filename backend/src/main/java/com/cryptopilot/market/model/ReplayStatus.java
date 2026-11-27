package com.cryptopilot.market.model;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A price replay running now (RF-07).
 *
 * @param scenario the scenario's name
 * @param market its market
 * @param symbol its pair
 * @param startedAt when it started
 * @param endsAt when its path ends; its last price is held after that until it is stopped
 * @param price the price it sent last, or its first one before any was sent
 */
public record ReplayStatus(
        String scenario, MarketType market, String symbol, Instant startedAt, Instant endsAt, BigDecimal price) {}
