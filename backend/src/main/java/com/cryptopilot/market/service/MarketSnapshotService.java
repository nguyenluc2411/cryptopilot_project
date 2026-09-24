package com.cryptopilot.market.service;

import com.cryptopilot.market.client.BinanceStreamProperties;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.repository.MarketSnapshotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the periodic market snapshots of NSF-03: one {@code spot_market_data} row per streamed Spot pair and
 * one {@code futures_market_data} row per streamed futures pair, every minute, from the latest stream values.
 *
 * <p>The snapshot time is the run's instant truncated to the minute, so a run repeated within the same minute
 * writes nothing twice. A value older than {@code snapshotMaxAge} is not written: while a stream is down the
 * snapshot series shows a hole, which is the truth, rather than repeating the last price as if it were
 * current.
 *
 * <p>Rule: NSF-03 (PERIODIC snapshots every minute); TECHNICAL_DESIGN 7.1 step 7.
 */
@Service
public class MarketSnapshotService {

    private final LatestMarketData latest;
    private final MarketSnapshotRepository snapshots;
    private final BinanceStreamProperties properties;
    private final Clock clock;

    public MarketSnapshotService(
            LatestMarketData latest,
            MarketSnapshotRepository snapshots,
            BinanceStreamProperties properties,
            Clock clock) {
        this.latest = latest;
        this.snapshots = snapshots;
        this.properties = properties;
        this.clock = clock;
    }

    /** Writes this minute's snapshots and answers what was written. */
    @Transactional
    public SnapshotRun writePeriodic() {
        Instant now = clock.instant();
        Instant at = now.truncatedTo(ChronoUnit.MINUTES);
        Instant oldest = now.minus(properties.snapshotMaxAge());
        Map<UUID, TickerMessage> allTickers = latest.tickers();
        Map<UUID, MarkPriceMessage> allMarkPrices = latest.markPrices();
        Map<UUID, TickerMessage> tickers = fresh(allTickers, oldest);
        Map<UUID, MarkPriceMessage> markPrices = fresh(allMarkPrices, oldest);
        int stale = allTickers.size() - tickers.size() + allMarkPrices.size() - markPrices.size();
        return new SnapshotRun(at, snapshots.insertSpot(at, tickers), snapshots.insertFutures(at, markPrices), stale);
    }

    private static <T extends StreamMessage> Map<UUID, T> fresh(Map<UUID, T> values, Instant oldest) {
        return values.entrySet().stream()
                .filter(entry -> !entry.getValue().eventTime().isBefore(oldest))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
