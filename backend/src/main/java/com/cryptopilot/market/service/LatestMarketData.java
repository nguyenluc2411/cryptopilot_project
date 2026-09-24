package com.cryptopilot.market.service;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The latest Spot ticker and futures mark price of each streamed pair, as NSF-03 last read them.
 *
 * <p>In memory, one entry per pair and market, overwritten by every message: the latest value wins, so the
 * store never grows with the message rate and the stream reader only ever does a map write. The periodic
 * snapshots of NSF-03 are written from it. Losing it loses nothing — the next message, a second later,
 * refills it — which is also what makes it a cache in the sense of TECHNICAL_DESIGN 5.6; moving it to Redis
 * and pushing it to clients is T-022's.
 *
 * <p>Rule: NSF-03 (latest prices are cached); BR-07 (a pair no longer streamed is dropped).
 */
@Component
public class LatestMarketData {

    private final Map<UUID, TickerMessage> tickers = new ConcurrentHashMap<>();
    private final Map<UUID, MarkPriceMessage> markPrices = new ConcurrentHashMap<>();

    /** Records a Spot ticker, unless an older message arrives after a newer one. */
    public void recordTicker(UUID pairId, TickerMessage ticker) {
        tickers.merge(pairId, ticker, (old, now) -> now.eventTime().isBefore(old.eventTime()) ? old : now);
    }

    /** Records a futures mark price, unless an older message arrives after a newer one. */
    public void recordMarkPrice(UUID pairId, MarkPriceMessage markPrice) {
        markPrices.merge(pairId, markPrice, (old, now) -> now.eventTime().isBefore(old.eventTime()) ? old : now);
    }

    /** The latest Spot ticker of a pair. */
    public Optional<TickerMessage> ticker(UUID pairId) {
        return Optional.ofNullable(tickers.get(pairId));
    }

    /** The latest futures mark price of a pair. */
    public Optional<MarkPriceMessage> markPrice(UUID pairId) {
        return Optional.ofNullable(markPrices.get(pairId));
    }

    /** Every Spot ticker held, by pair. */
    public Map<UUID, TickerMessage> tickers() {
        return Map.copyOf(tickers);
    }

    /** Every futures mark price held, by pair. */
    public Map<UUID, MarkPriceMessage> markPrices() {
        return Map.copyOf(markPrices);
    }

    /** Drops the pairs of a market that are no longer streamed, so nothing is snapshotted for them (BR-07). */
    public void retainOnly(MarketType market, Collection<UUID> streamed) {
        Set<UUID> keep = Set.copyOf(streamed);
        (market == MarketType.SPOT ? tickers : markPrices).keySet().retainAll(keep);
    }
}
