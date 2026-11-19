package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.config.MarketHealthProperties;
import com.cryptopilot.market.model.enums.MarketType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * When each market's stream last delivered data, and since when it has delivered without a break (D-158): the two
 * signals of the health monitor that come from the stream. Written by the stream readers on every message, so a write
 * costs a lock held for two field reads and writes.
 *
 * <p>A break is a silence of at least {@code reconnectingAfter}; the run of live data after it starts anew. Before the
 * first message, both read as the start of the process, so a stream that never connects is paused after
 * {@code pauseAfter}.
 *
 * <p>Rule: NSF-03; D-158.
 */
@Component
public class MarketLiveness {

    private final Duration breakAfter;
    private final Map<MarketType, Run> runs = new EnumMap<>(MarketType.class);

    public MarketLiveness(MarketHealthProperties properties, Clock clock) {
        this.breakAfter = properties.reconnectingAfter();
        Instant start = clock.instant();
        for (MarketType market : MarketType.values()) {
            runs.put(market, new Run(start));
        }
    }

    /** A message of the market arrived at {@code at}. */
    public void record(MarketType market, Instant at) {
        runs.get(market).record(at, breakAfter);
    }

    /** When the market's latest live data arrived. */
    public Instant lastLiveAt(MarketType market) {
        return runs.get(market).lastLiveAt();
    }

    /** When the market's current unbroken run of live data began. */
    public Instant liveSince(MarketType market) {
        return runs.get(market).liveSince();
    }

    private static final class Run {

        private Instant lastLiveAt;
        private Instant liveSince;

        private Run(Instant start) {
            this.lastLiveAt = start;
            this.liveSince = start;
        }

        private synchronized void record(Instant at, Duration breakAfter) {
            if (at.isBefore(lastLiveAt)) {
                return;
            }
            if (Duration.between(lastLiveAt, at).compareTo(breakAfter) >= 0) {
                liveSince = at;
            }
            lastLiveAt = at;
        }

        private synchronized Instant lastLiveAt() {
            return lastLiveAt;
        }

        private synchronized Instant liveSince() {
            return liveSince;
        }
    }
}
