package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A price replay running now (RF-07).
 *
 * @param scenario its scenario
 * @param market its market
 * @param symbol its pair, whose prices come from the replay rather than the exchange
 * @param startedAt when it started
 * @param endsAt when its path ends; its last price is held after that until it is stopped
 * @param price the price it sent last
 */
public record ReplayResponse(
        String scenario, MarketType market, String symbol, Instant startedAt, Instant endsAt, BigDecimal price) {}
