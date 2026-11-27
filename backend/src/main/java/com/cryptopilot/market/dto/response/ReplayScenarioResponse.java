package com.cryptopilot.market.dto.response;

import com.cryptopilot.market.model.enums.MarketType;
import java.math.BigDecimal;

/**
 * A price replay scenario the environment can play (RF-07).
 *
 * @param name its name, to start it with
 * @param description what it shows
 * @param market its market
 * @param symbol its pair
 * @param lengthSeconds how long its path runs before its last price is held
 * @param firstPrice its price at the start
 * @param lastPrice its price at the end, held until it is stopped
 */
public record ReplayScenarioResponse(
        String name,
        String description,
        MarketType market,
        String symbol,
        long lengthSeconds,
        BigDecimal firstPrice,
        BigDecimal lastPrice) {}
