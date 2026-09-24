package com.cryptopilot.market.event;

import com.cryptopilot.market.MarketType;

/**
 * NSF-01 has synchronised one market: the pairs' exchange statuses are current. Published by the symbol
 * synchronisation job after a successful run, so that the candle backfill (NSF-02) starts from statuses the
 * exchange has just confirmed rather than racing the first synchronisation at start-up.
 *
 * <p>Rule: NSF-01, NSF-02.
 *
 * @param market the market synchronised
 */
public record SymbolsSynchronised(MarketType market) {}
