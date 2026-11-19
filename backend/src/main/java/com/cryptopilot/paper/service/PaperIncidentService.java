package com.cryptopilot.paper.service;

import com.cryptopilot.market.model.enums.MarketType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the notices of a market incident need from paper trading (D-158): who has something waiting on the market's
 * prices, and what a catch-up did to whom.
 *
 * <p>Rule: NSF-03; D-158.
 */
public interface PaperIncidentService {

    /** The Traders with a working order on the market, or, on Futures, an open position. */
    List<UUID> tradersOn(MarketType market);

    /** How many fills a replay applied to each Trader's orders on the market, from {@code from} to {@code to}. */
    Map<UUID, Long> replayedFills(MarketType market, Instant from, Instant to);

    /** How many Futures positions of each Trader were liquidated from {@code from} to {@code to}. */
    Map<UUID, Long> liquidations(Instant from, Instant to);
}
