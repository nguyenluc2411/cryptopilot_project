package com.cryptopilot.market.service.impl;

import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.client.fallback.OkxCandle;
import com.cryptopilot.market.client.fallback.OkxClient;
import com.cryptopilot.market.client.fallback.OkxException;
import com.cryptopilot.market.client.fallback.OkxProperties;
import com.cryptopilot.market.client.fallback.OkxTicker;
import com.cryptopilot.market.dto.response.FallbackCandleResponse;
import com.cryptopilot.market.dto.response.FallbackPriceResponse;
import com.cryptopilot.market.dto.response.FallbackPricesResponse;
import com.cryptopilot.market.event.MarketFallbackUnavailable;
import com.cryptopilot.market.model.MarketIncident;
import com.cryptopilot.market.model.StreamTarget;
import com.cryptopilot.market.model.enums.DisplaySource;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.service.DisplayPriceService;
import com.cryptopilot.market.service.MarketHealthService;
import com.cryptopilot.market.service.StreamCandleService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds the display-only fallback prices of each market whose exchange data is lost (D-159): one OKX call per market
 * and poll gives every ticker, matched to the pairs the market streams.
 *
 * <ul>
 *   <li>A pair matches the OKX instrument its symbol names, {@code BTCUSDT} the Spot {@code BTC-USDT} and the swap
 *       {@code BTC-USDT-SWAP}, unless {@code overrides} names another; one OKX does not list has no reference price.
 *   <li>A price further from the exchange's last one (the Spot last price, the Futures mark) than {@code divergence} is
 *       flagged, so the banner warns it may differ markedly.
 *   <li>A failed read leaves no prices, and is told to the administrators once per incident (E9).
 *   <li>The prices are shown only while the market's data is lost: paused, or recovering from an incident that lost
 *       data; while live, under maintenance and recovering from a maintenance the exchange's own prices are.
 * </ul>
 *
 * <p>Rule: BR-09; NSF-03; D-159.
 */
@Service
public class DisplayPriceServiceImpl implements DisplayPriceService {

    private static final Logger log = LoggerFactory.getLogger(DisplayPriceServiceImpl.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ObjectProvider<OkxClient> okx;
    private final OkxProperties properties;
    private final StreamCandleService streamed;
    private final LatestMarketData latest;
    private final MarketHealthService health;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Map<MarketType, Snapshot> snapshots = new EnumMap<>(MarketType.class);
    private final Map<MarketType, UUID> unavailableTold = new EnumMap<>(MarketType.class);

    public DisplayPriceServiceImpl(
            ObjectProvider<OkxClient> okx,
            OkxProperties properties,
            StreamCandleService streamed,
            LatestMarketData latest,
            MarketHealthService health,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.okx = okx;
        this.properties = properties;
        this.streamed = streamed;
        this.latest = latest;
        this.health = health;
        this.events = events;
        this.transactions = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Override
    public void refresh(MarketType market) {
        OkxClient client = okx.getIfAvailable();
        if (client == null) {
            return;
        }
        List<OkxTicker> tickers;
        try {
            tickers = market == MarketType.SPOT ? client.spotTickers() : client.swapTickers();
        } catch (OkxException failure) {
            synchronized (snapshots) {
                snapshots.remove(market);
            }
            log.warn("D-159 {} fallback prices not read: {}", market, failure.getMessage());
            tellUnavailable(market, failure.getMessage());
            return;
        }
        Map<String, OkxTicker> byInstrument = new HashMap<>();
        tickers.forEach(ticker -> byInstrument.put(ticker.instrumentId(), ticker));
        List<FallbackPriceResponse> prices = new ArrayList<>();
        Map<String, String> instruments = new HashMap<>();
        for (StreamTarget target : streamed.targets(market)) {
            String instrument = instrumentOf(market, target.symbol());
            OkxTicker ticker = byInstrument.get(instrument);
            if (ticker == null) {
                continue;
            }
            instruments.put(target.symbol(), instrument);
            prices.add(new FallbackPriceResponse(
                    target.symbol(),
                    ticker.lastPrice(),
                    change(ticker),
                    ticker.high24h(),
                    ticker.low24h(),
                    ticker.volume24h(),
                    ticker.at(),
                    diverged(ticker.lastPrice(), exchangePrice(market, target.pairId()))));
        }
        prices.sort((a, b) -> a.symbol().compareTo(b.symbol()));
        synchronized (snapshots) {
            snapshots.put(market, new Snapshot(clock.instant(), List.copyOf(prices), Map.copyOf(instruments)));
            unavailableTold.remove(market);
        }
    }

    @Override
    public void clear(MarketType market) {
        synchronized (snapshots) {
            snapshots.remove(market);
            unavailableTold.remove(market);
        }
    }

    @Override
    public FallbackPricesResponse prices(MarketType market) {
        DisplaySource source = source(market);
        Snapshot snapshot = source == DisplaySource.OKX ? snapshot(market) : null;
        return snapshot == null
                ? new FallbackPricesResponse(market, source, null, List.of())
                : new FallbackPricesResponse(market, source, snapshot.readAt(), snapshot.prices());
    }

    @Override
    public DisplaySource source(MarketType market) {
        if (!lost(market)) {
            return DisplaySource.BINANCE;
        }
        return snapshot(market) == null ? DisplaySource.NONE : DisplaySource.OKX;
    }

    @Override
    public List<FallbackCandleResponse> candles(MarketType market, String symbol, int limit) {
        if (limit < 1 || limit > 300) {
            throw new FieldValidationException("limit must be 1 to 300, not " + limit, Map.of("limit", "MSG01"));
        }
        OkxClient client = okx.getIfAvailable();
        Snapshot snapshot = snapshot(market);
        if (client == null || snapshot == null || !lost(market)) {
            return List.of();
        }
        String instrument = snapshot.instruments().get(symbol.toUpperCase(Locale.ROOT));
        if (instrument == null) {
            return List.of();
        }
        try {
            return client.minuteCandles(instrument, limit).stream()
                    .map(DisplayPriceServiceImpl::response)
                    .toList();
        } catch (OkxException failure) {
            log.warn("D-159 {} {} fallback candles not read: {}", market, symbol, failure.getMessage());
            return List.of();
        }
    }

    /** Whether the market's exchange data is lost: paused or recovering, the states the fallback is shown in. */
    private boolean lost(MarketType market) {
        return health.dataLost(market);
    }

    private Snapshot snapshot(MarketType market) {
        synchronized (snapshots) {
            return snapshots.get(market);
        }
    }

    /** The OKX instrument of an exchange symbol: an override, or the symbol split before its quote coin. */
    String instrumentOf(MarketType market, String symbol) {
        String override = properties.overrides().get(market.name() + "." + symbol.toUpperCase(Locale.ROOT));
        if (override != null) {
            return override;
        }
        String upper = symbol.toUpperCase(Locale.ROOT);
        for (String quote : QUOTES) {
            if (upper.endsWith(quote) && upper.length() > quote.length()) {
                String spot = upper.substring(0, upper.length() - quote.length()) + "-" + quote;
                return market == MarketType.SPOT ? spot : spot + "-SWAP";
            }
        }
        return upper;
    }

    /** The quote coins an exchange symbol may end with, longest first so {@code FDUSD} is tried before {@code USD}. */
    private static final List<String> QUOTES = List.of("FDUSD", "USDT", "USDC", "BTC", "ETH", "BNB", "EUR", "TRY");

    private Optional<BigDecimal> exchangePrice(MarketType market, UUID pairId) {
        return market == MarketType.SPOT
                ? latest.ticker(pairId).map(TickerMessage::lastPrice)
                : latest.markPrice(pairId).map(MarkPriceMessage::markPrice);
    }

    private boolean diverged(BigDecimal fallback, Optional<BigDecimal> exchange) {
        if (exchange.isEmpty() || exchange.get().signum() <= 0) {
            return false;
        }
        BigDecimal gap = fallback.subtract(exchange.get()).abs().divide(exchange.get(), MathContext.DECIMAL64);
        return gap.compareTo(properties.divergence()) > 0;
    }

    private static BigDecimal change(OkxTicker ticker) {
        if (ticker.open24h() == null || ticker.open24h().signum() <= 0) {
            return null;
        }
        return ticker.lastPrice()
                .subtract(ticker.open24h())
                .multiply(HUNDRED)
                .divide(ticker.open24h(), 2, RoundingMode.HALF_UP);
    }

    /** Tells the administrators once per incident that the fallback could not be read either (E9). */
    private void tellUnavailable(MarketType market, String failure) {
        Optional<UUID> incident = health.openIncident(market).map(MarketIncident::id);
        if (incident.isEmpty()) {
            return;
        }
        synchronized (snapshots) {
            if (incident.get().equals(unavailableTold.get(market))) {
                return;
            }
            unavailableTold.put(market, incident.get());
        }
        transactions.executeWithoutResult(
                status -> events.publishEvent(new MarketFallbackUnavailable(market, incident.get(), failure)));
    }

    private static FallbackCandleResponse response(OkxCandle candle) {
        return new FallbackCandleResponse(
                candle.openTime(),
                candle.open(),
                candle.high(),
                candle.low(),
                candle.close(),
                candle.volume(),
                candle.closed());
    }

    /** The prices of one read, and the OKX instrument of each pair they name. */
    private record Snapshot(Instant readAt, List<FallbackPriceResponse> prices, Map<String, String> instruments) {}
}
