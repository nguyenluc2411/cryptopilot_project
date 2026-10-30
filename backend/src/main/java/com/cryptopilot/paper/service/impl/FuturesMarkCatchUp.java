package com.cryptopilot.paper.service.impl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The Futures pairs whose mark triggers and liquidations the matching engine is catching up on after a lost mark
 * stream or a restart (TR-42, plan 9.5): such a pair stays {@code MARK_STALE} however current its latest mark is, until
 * the engine replayed the missed mark candles and two live marks followed. Written by the engine's consumer thread,
 * read by any request.
 *
 * <p>Rule: TR-42; D-127.
 */
@Component
class FuturesMarkCatchUp {

    private final Set<String> symbols = ConcurrentHashMap.newKeySet();

    /** Marks the pair as catching up, or as live again. */
    void set(String symbol, boolean catchingUp) {
        if (catchingUp) {
            symbols.add(symbol);
        } else {
            symbols.remove(symbol);
        }
    }

    /** Whether the engine is catching up on the pair's marks. */
    boolean catchingUp(String symbol) {
        return symbols.contains(symbol);
    }
}
