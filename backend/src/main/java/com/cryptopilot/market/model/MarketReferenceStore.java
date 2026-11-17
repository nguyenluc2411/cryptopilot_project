package com.cryptopilot.market.model;

import com.cryptopilot.market.client.CoinPaprikaGlobal;
import com.cryptopilot.market.client.FearGreedReading;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The latest reference figures of the market list that do not come from Binance: the whole market's cap (CoinPaprika),
 * each listed coin's circulating supply (CoinPaprika) and the Fear &amp; Greed Index (alternative.me).
 *
 * <p>Held in memory, not stored: they change slowly, are read again minutes after a restart, and a missing one only
 * blanks its figure on the page. Written by the reference jobs, read by the market list; each figure is replaced whole,
 * so a reader never sees half of an update.
 *
 * <p>Rule: SRS 3.3.1.
 */
@Component
public class MarketReferenceStore {

    /**
     * One coin's supply.
     *
     * @param rank its rank by market cap on CoinPaprika
     * @param circulatingSupply the coins in circulation
     */
    public record Supply(int rank, BigDecimal circulatingSupply) {}

    /**
     * The supplies of one read, by Binance symbol.
     *
     * @param bySymbol the supply of each listed coin CoinPaprika knows
     * @param readAt when they were read
     */
    public record Supplies(Map<String, Supply> bySymbol, Instant readAt) {}

    private final AtomicReference<CoinPaprikaGlobal> global = new AtomicReference<>();
    private final AtomicReference<Supplies> supplies = new AtomicReference<>();
    private final AtomicReference<FearGreedReading> fearGreed = new AtomicReference<>();

    public Optional<CoinPaprikaGlobal> global() {
        return Optional.ofNullable(global.get());
    }

    public Optional<Supplies> supplies() {
        return Optional.ofNullable(supplies.get());
    }

    public Optional<FearGreedReading> fearGreed() {
        return Optional.ofNullable(fearGreed.get());
    }

    public void global(CoinPaprikaGlobal latest) {
        global.set(latest);
    }

    public void supplies(Map<String, Supply> bySymbol, Instant readAt) {
        supplies.set(new Supplies(Map.copyOf(bySymbol), readAt));
    }

    public void fearGreed(FearGreedReading latest) {
        fearGreed.set(latest);
    }
}
