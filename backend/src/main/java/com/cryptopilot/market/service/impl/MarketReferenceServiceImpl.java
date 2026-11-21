package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.client.CoinPaprikaClient;
import com.cryptopilot.market.client.CoinPaprikaCoin;
import com.cryptopilot.market.client.CoinPaprikaException;
import com.cryptopilot.market.client.CoinPaprikaProperties;
import com.cryptopilot.market.client.CoinPaprikaTicker;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.model.CoinPaprikaDirectory;
import com.cryptopilot.market.model.MarketReferenceStore;
import com.cryptopilot.market.model.MarketReferenceStore.Supply;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.service.MarketReferenceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads CoinPaprika's figures for the market list into the {@link MarketReferenceStore}: the whole market's cap on every
 * call, and the circulating supply of the listed coins at most every {@link #SUPPLY_EVERY}, matched to the coins the way
 * their names and logos are ({@link CoinPaprikaDirectory}).
 *
 * <p>A failed read keeps the figures of the last good one; the next call tries again. A coin listed between two reads
 * gets its supply from {@link #refreshSupplies()}, which reads them at once.
 *
 * <p>Rule: SRS 3.3.1.
 */
public class MarketReferenceServiceImpl implements MarketReferenceService {

    private static final Logger log = LoggerFactory.getLogger(MarketReferenceServiceImpl.class);

    /** How often the coins' supplies are read; supply moves slowly. */
    static final Duration SUPPLY_EVERY = Duration.ofHours(1);

    private final CoinPaprikaClient client;
    private final CoinPaprikaProperties properties;
    private final CoinRepository coins;
    private final MarketReferenceStore store;
    private final Clock clock;

    public MarketReferenceServiceImpl(
            CoinPaprikaClient client,
            CoinPaprikaProperties properties,
            CoinRepository coins,
            MarketReferenceStore store,
            Clock clock) {
        this.client = client;
        this.properties = properties;
        this.coins = coins;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void refresh() {
        readGlobal();
        Instant now = clock.instant();
        boolean stale = store.supplies()
                .map(read -> !read.readAt().plus(SUPPLY_EVERY).isAfter(now))
                .orElse(true);
        if (stale) {
            readSupplies(now);
        }
    }

    @Override
    public void refreshSupplies() {
        readSupplies(clock.instant());
    }

    private void readGlobal() {
        try {
            store.global(client.global());
        } catch (CoinPaprikaException failure) {
            log.warn("Market reference: CoinPaprika global figures failed ({})", failure.getMessage());
        } catch (RuntimeException unexpected) {
            log.error("Market reference: global figures failed unexpectedly", unexpected);
        }
    }

    private void readSupplies(Instant now) {
        try {
            List<CoinPaprikaTicker> tickers = client.tickers();
            Map<String, CoinPaprikaTicker> byId = new HashMap<>();
            tickers.forEach(ticker -> byId.put(ticker.id(), ticker));
            CoinPaprikaDirectory directory = new CoinPaprikaDirectory(
                    tickers.stream()
                            .map(t -> new CoinPaprikaCoin(t.id(), t.symbol(), t.symbol(), t.rank(), true))
                            .toList(),
                    properties.overrides());
            Map<String, Supply> supplies = new HashMap<>();
            for (Coin coin : coins.findListed()) {
                directory
                        .find(coin.getSymbol())
                        .map(found -> byId.get(found.id()))
                        .filter(ticker -> ticker.circulatingSupply() != null)
                        .ifPresent(ticker ->
                                supplies.put(coin.getSymbol(), new Supply(ticker.rank(), ticker.circulatingSupply())));
            }
            store.supplies(supplies, now);
            log.info("Market reference: supplies of {} listed coins read from CoinPaprika", supplies.size());
        } catch (CoinPaprikaException failure) {
            log.warn("Market reference: CoinPaprika supplies failed ({}); the last ones stay", failure.getMessage());
        } catch (RuntimeException unexpected) {
            log.error("Market reference: supplies failed unexpectedly", unexpected);
        }
    }
}
