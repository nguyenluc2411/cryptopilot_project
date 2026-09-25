package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.client.StreamMessage;
import com.cryptopilot.market.client.StreamMessage.KlineMessage;
import com.cryptopilot.market.client.StreamMessage.MarkPriceMessage;
import com.cryptopilot.market.client.StreamMessage.TickerMessage;
import com.cryptopilot.market.dto.response.KlineUpdateResponse;
import com.cryptopilot.market.dto.response.MarketOverviewResponse;
import com.cryptopilot.market.dto.response.TickerUpdateResponse;
import com.cryptopilot.market.service.MarketBroadcastService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

/**
 * The public market topics of TECHNICAL_DESIGN 9, fed from the stream and pushed on a rhythm rather than per message.
 *
 * <h2>Coalescing</h2>
 *
 * <p>{@link #publish} runs on the stream reader's thread and only replaces the update waiting for its destination with
 * a newer one (by the exchange's instant); {@link #pushUpdates} sends each destination at most once, so a ticker
 * destination gets at most one message per push interval — one per second by default, TECHNICAL_DESIGN 9 — however
 * fast the exchange streams. A kline destination keeps one update per candle rather than one per destination: the
 * closed candle and the first update of the next one may fall in the same interval, and the closed one must not be
 * replaced by it. {@link #pushOverviews} sends each market's latest tickers every overview interval (2 s).
 *
 * <h2>No client waits for another</h2>
 *
 * <p>The push hands messages to the broker, which delivers them on its outbound channel, one session at a time per
 * session; a session that cannot keep up is buffered and, past the configured limit, closed by Spring's session
 * decorator, while the others carry on. Nothing here writes to a socket, and a failure to hand over a message is logged
 * and does not stop the push of the others.
 *
 * <p>Rule: NSF-03; BR-08; SRS 3.3.1, 4.2.3 (realtime price delivery within 2 s); TECHNICAL_DESIGN 9; D-50, D-51.
 */
@Service
public class MarketBroadcastServiceImpl implements MarketBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(MarketBroadcastService.class);

    private final Map<String, TickerUpdateResponse> tickers = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<Instant, KlineUpdateResponse>> klines = new ConcurrentHashMap<>();
    private final Map<MarketType, Map<String, TickerUpdateResponse>> overview = new EnumMap<>(MarketType.class);
    private final SimpMessageSendingOperations broker;
    private final Clock clock;

    public MarketBroadcastServiceImpl(SimpMessageSendingOperations broker, Clock clock) {
        this.broker = broker;
        this.clock = clock;
        for (MarketType market : MarketType.values()) {
            overview.put(market, new ConcurrentHashMap<>());
        }
    }

    @Override
    public void publish(MarketType market, StreamMessage message) {
        switch (message) {
            case TickerMessage ticker -> ticker(market, spot(market, ticker));
            case MarkPriceMessage mark -> ticker(market, futures(market, mark));
            case KlineMessage kline -> {
                if (CandleBackfillServiceImpl.TIMEFRAMES.contains(kline.interval())) {
                    KlineUpdateResponse update = kline(market, kline);
                    // Atomic per destination against the push's remove, so no update lands in a map already taken.
                    klines.compute(klineDestination(update), (destination, candles) -> {
                        NavigableMap<Instant, KlineUpdateResponse> waiting =
                                candles == null ? new TreeMap<>() : candles;
                        waiting.merge(update.openTime(), update, MarketBroadcastServiceImpl::newerKline);
                        return waiting;
                    });
                }
            }
        }
    }

    @Override
    public int pushUpdates() {
        int sent = 0;
        for (String destination : List.copyOf(tickers.keySet())) {
            TickerUpdateResponse update = tickers.remove(destination);
            if (update != null && send(destination, update)) {
                sent++;
            }
        }
        for (String destination : List.copyOf(klines.keySet())) {
            NavigableMap<Instant, KlineUpdateResponse> candles = klines.remove(destination);
            if (candles != null) {
                for (KlineUpdateResponse update : candles.values()) {
                    sent += send(destination, update) ? 1 : 0;
                }
            }
        }
        return sent;
    }

    @Override
    public int pushOverviews() {
        int sent = 0;
        for (Map.Entry<MarketType, Map<String, TickerUpdateResponse>> market : overview.entrySet()) {
            if (market.getValue().isEmpty()) {
                continue;
            }
            List<TickerUpdateResponse> latest = market.getValue().values().stream()
                    .sorted(Comparator.comparing(TickerUpdateResponse::symbol))
                    .toList();
            MarketOverviewResponse body =
                    new MarketOverviewResponse(market.getKey().name(), clock.instant(), latest);
            sent += send("/topic/overview." + market.getKey().name(), body) ? 1 : 0;
        }
        return sent;
    }

    /** {@code /topic/ticker.{market}.{symbol}}. */
    static String tickerDestination(String market, String symbol) {
        return "/topic/ticker." + market + "." + symbol;
    }

    /** {@code /topic/kline.{market}.{symbol}.{tf}}. */
    static String klineDestination(KlineUpdateResponse update) {
        return "/topic/kline." + update.market() + "." + update.symbol() + "." + update.timeframe();
    }

    private void ticker(MarketType market, TickerUpdateResponse update) {
        tickers.merge(
                tickerDestination(update.market(), update.symbol()), update, MarketBroadcastServiceImpl::newerTicker);
        overview.get(market).merge(update.symbol(), update, MarketBroadcastServiceImpl::newerTicker);
    }

    private boolean send(String destination, Object payload) {
        try {
            broker.convertAndSend(destination, payload);
            return true;
        } catch (RuntimeException failure) {
            log.warn("Market topic {} could not be pushed: {}", destination, failure.toString());
            return false;
        }
    }

    private static TickerUpdateResponse spot(MarketType market, TickerMessage ticker) {
        return new TickerUpdateResponse(
                market.name(),
                ticker.symbol(),
                ticker.eventTime(),
                ticker.lastPrice(),
                ticker.bestBidPrice(),
                ticker.bestAskPrice(),
                ticker.highPrice24h(),
                ticker.lowPrice24h(),
                ticker.priceChangePercent24h(),
                ticker.baseVolume24h(),
                ticker.quoteVolume24h(),
                null,
                null,
                null,
                null);
    }

    private static TickerUpdateResponse futures(MarketType market, MarkPriceMessage mark) {
        return new TickerUpdateResponse(
                market.name(),
                mark.symbol(),
                mark.eventTime(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                mark.markPrice(),
                mark.indexPrice(),
                mark.fundingRate(),
                mark.nextFundingTime());
    }

    private static KlineUpdateResponse kline(MarketType market, KlineMessage message) {
        return new KlineUpdateResponse(
                market.name(),
                message.symbol(),
                message.interval().code(),
                message.kline().openTime(),
                message.kline().closeTime(),
                message.kline().open(),
                message.kline().high(),
                message.kline().low(),
                message.kline().close(),
                message.kline().volume(),
                message.kline().quoteVolume(),
                message.closed(),
                message.eventTime());
    }

    private static TickerUpdateResponse newerTicker(TickerUpdateResponse waiting, TickerUpdateResponse arriving) {
        return arriving.sourceTime().isBefore(waiting.sourceTime()) ? waiting : arriving;
    }

    /** The later message of one candle; a closed candle is never replaced by a forming report of itself. */
    private static KlineUpdateResponse newerKline(KlineUpdateResponse waiting, KlineUpdateResponse arriving) {
        if (waiting.closed() && !arriving.closed()) {
            return waiting;
        }
        return arriving.sourceTime().isBefore(waiting.sourceTime()) ? waiting : arriving;
    }
}
