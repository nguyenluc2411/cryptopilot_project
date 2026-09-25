package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.BinanceClientException;
import com.cryptopilot.market.client.BinanceRestClient;
import com.cryptopilot.market.client.ExchangeSymbol;
import com.cryptopilot.market.config.SymbolSyncProperties;
import com.cryptopilot.market.model.SyncReport;
import com.cryptopilot.market.service.SymbolSyncService;
import com.cryptopilot.market.service.SymbolSyncWriter;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * NSF-01, one market at a time: read the exchange information, then reconcile it with the pair table.
 *
 * <p>The exchange is read <em>outside</em> any transaction and the table is written inside one, by
 * {@link SymbolSyncWriter}: a database connection is not held open across an HTTP call to the exchange, and
 * the write is all or nothing for the market. The two markets are two calls, two transactions: a failure
 * reading or writing futures leaves a Spot synchronisation that already committed exactly as it is.
 *
 * <p>A refusal of the exchange is not handled here. It propagates as the client's
 * {@link BinanceClientException}, and the caller — the scheduled job — applies the client's caller contract:
 * reschedule at or after {@code retryAt}, wait for the next run, or log a defect.
 *
 * <p>Rule: NSF-01; BR-07, BR-09; TECHNICAL_DESIGN 7.1 and 7.1.2.
 */
@Service
public class SymbolSyncServiceImpl implements SymbolSyncService {

    private final BinanceRestClient exchange;
    private final SymbolSyncWriter writer;
    private final List<String> seedSymbols;
    private final Clock clock;

    public SymbolSyncServiceImpl(
            BinanceRestClient exchange, SymbolSyncWriter writer, SymbolSyncProperties properties, Clock clock) {
        this.exchange = Objects.requireNonNull(exchange, "exchange must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.seedSymbols = List.copyOf(properties.seedSymbols());
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Synchronises one market and reports what changed.
     *
     * @throws BinanceClientException when the exchange refuses; nothing has been written then
     */
    public SyncReport sync(MarketType market) {
        Objects.requireNonNull(market, "market must not be null");
        List<ExchangeSymbol> listed =
                market == MarketType.SPOT ? exchange.spotExchangeInfo() : exchange.futuresExchangeInfo();
        return writer.apply(market, listed, seedSymbols, clock.instant());
    }
}
