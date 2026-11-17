package com.cryptopilot.market.service.impl;

import com.cryptopilot.market.client.CoinPaprikaGlobal;
import com.cryptopilot.market.dto.response.CoinMarketResponse;
import com.cryptopilot.market.dto.response.CoinMarketResponse.AltcoinSeason;
import com.cryptopilot.market.dto.response.CoinMarketResponse.CoinRow;
import com.cryptopilot.market.dto.response.CoinMarketResponse.FearGreed;
import com.cryptopilot.market.dto.response.CoinMarketResponse.GlobalMarket;
import com.cryptopilot.market.dto.response.CoinMarketResponse.OpenInterest;
import com.cryptopilot.market.dto.response.CoinMarketResponse.Point;
import com.cryptopilot.market.entity.Coin;
import com.cryptopilot.market.entity.CryptoPair;
import com.cryptopilot.market.model.MarketReferenceStore;
import com.cryptopilot.market.model.MarketReferenceStore.Supply;
import com.cryptopilot.market.model.enums.MarketInterval;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CoinMarketRepository;
import com.cryptopilot.market.repository.CoinMarketRepository.OpenInterestPoint;
import com.cryptopilot.market.repository.CoinMarketRepository.Series;
import com.cryptopilot.market.repository.CoinRepository;
import com.cryptopilot.market.repository.CryptoPairRepository;
import com.cryptopilot.market.service.CoinMarketService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the market list of coins from the listed pairs, the stored candles and open interest, and the reference
 * figures CoinPaprika and alternative.me give.
 *
 * <h2>What a coin is</h2>
 *
 * <p>The base coin of an ACTIVE pair enabled on Spot or Futures (BR-07); a coin that is only ever a quote, such as
 * USDT, has no price of its own here and is left out. Its series is its Spot pair's when it has one, else its Futures
 * pair's; with several, the one quoted in USDT, then the first in display order. The row names that quote, which the
 * past prices and the small chart are in.
 *
 * <h2>How it is served</h2>
 *
 * <p>A job rebuilds the list every minute ({@link #refresh()}) and requests read the last one built; only the very
 * first request before any refresh builds it. A failed build keeps the previous list. One build reads a fixed number of
 * statements whatever the number of coins.
 *
 * <p>Rule: SRS 3.3.1; UC-09; BR-07, BR-08.
 *
 * <p>Reference: Nottingham, M. (2010). RFC 5861, <i>HTTP Cache-Control Extensions for Stale Content</i> (serving the
 * last good answer while a new one is made).
 */
@Service
public class CoinMarketServiceImpl implements CoinMarketService {

    private static final Logger log = LoggerFactory.getLogger(CoinMarketServiceImpl.class);

    /** Coins pegged to a currency, a metal or another coin, left out of the altcoin season. */
    static final Set<String> PEGGED = Set.of(
            "USDT", "USDC", "FDUSD", "DAI", "TUSD", "USDE", "USDP", "PYUSD", "BUSD", "USD1", "XUSD", "EUR", "AEUR",
            "EURI", "WBTC", "WBETH", "PAXG");

    /** The period of the altcoin season, in days. */
    static final int ALTCOIN_SEASON_DAYS = 90;

    /** The fewest altcoins the season is computed over. */
    static final int ALTCOIN_SEASON_MIN = 3;

    private static final String QUOTE = "USDT";
    private static final MathContext PERCENT = new MathContext(12, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CryptoPairRepository pairs;
    private final CoinRepository coins;
    private final CoinMarketRepository figures;
    private final MarketReferenceStore reference;
    private final Clock clock;
    private final ReentrantLock building = new ReentrantLock();
    private final AtomicReference<CoinMarketResponse> latest = new AtomicReference<>();

    public CoinMarketServiceImpl(
            CryptoPairRepository pairs,
            CoinRepository coins,
            CoinMarketRepository figures,
            MarketReferenceStore reference,
            Clock clock) {
        this.pairs = pairs;
        this.coins = coins;
        this.figures = figures;
        this.reference = reference;
        this.clock = clock;
    }

    @Override
    public CoinMarketResponse coins() {
        CoinMarketResponse current = latest.get();
        if (current != null) {
            return current;
        }
        building.lock();
        try {
            current = latest.get();
            if (current == null) {
                current = build(clock.instant());
                latest.set(current);
            }
            return current;
        } finally {
            building.unlock();
        }
    }

    @Override
    public void refresh() {
        if (!building.tryLock()) {
            return;
        }
        try {
            latest.set(build(clock.instant()));
        } catch (RuntimeException failure) {
            log.warn("Coin list: rebuild failed, the previous list stays ({})", failure.toString());
        } finally {
            building.unlock();
        }
    }

    /** One listed coin, its pairs and the quote of its series. */
    private record Listed(Coin coin, CryptoPair spot, CryptoPair futures, String quote) {

        CryptoPair pair() {
            return spot != null ? spot : futures;
        }

        Series series() {
            return new Series(pair().getId(), spot != null ? MarketType.SPOT : MarketType.FUTURES);
        }
    }

    CoinMarketResponse build(Instant now) {
        List<CryptoPair> spot = pairs.findEnabledOnSpot();
        List<CryptoPair> futures = pairs.findEnabledOnFutures();
        Set<UUID> coinIds = new HashSet<>();
        for (CryptoPair pair : spot) {
            coinIds.add(pair.getBaseCoinId());
            coinIds.add(pair.getQuoteCoinId());
        }
        for (CryptoPair pair : futures) {
            coinIds.add(pair.getBaseCoinId());
            coinIds.add(pair.getQuoteCoinId());
        }
        Map<UUID, Coin> byId = new HashMap<>();
        coins.findAllByIdIn(coinIds).forEach(coin -> byId.put(coin.getId(), coin));
        Map<UUID, Listed> listed = listed(spot, futures, byId);

        List<Series> series = listed.values().stream().map(Listed::series).toList();
        Map<Series, BigDecimal> hourAgo = figures.closesAt(
                series,
                MarketInterval.FIFTEEN_MINUTES,
                now.minus(Duration.ofHours(1)),
                now.minus(Duration.ofMinutes(90)));
        Map<Series, BigDecimal> weekAgo = figures.closesAt(
                series,
                MarketInterval.ONE_HOUR,
                now.minus(Duration.ofDays(7)),
                now.minus(Duration.ofDays(7).plusHours(3)));
        Map<Series, List<BigDecimal>> week =
                figures.closesBetween(series, MarketInterval.ONE_HOUR, now.minus(Duration.ofDays(7)), now);

        Map<String, Supply> supplies = reference
                .supplies()
                .map(MarketReferenceStore.Supplies::bySymbol)
                .orElse(Map.of());
        List<CoinRow> rows = new ArrayList<>();
        for (Listed coin : listed.values()) {
            Series key = coin.series();
            rows.add(row(
                    coin,
                    supplies.get(coin.coin().getSymbol()),
                    hourAgo.get(key),
                    weekAgo.get(key),
                    week.getOrDefault(key, List.of())));
        }
        rows.sort(Comparator.comparing((CoinRow row) -> row.rank() == null ? Integer.MAX_VALUE : row.rank())
                .thenComparing(CoinRow::symbol));

        return new CoinMarketResponse(
                now,
                reference.global().map(CoinMarketServiceImpl::global).orElse(null),
                reference
                        .fearGreed()
                        .map(read -> new FearGreed(read.value(), read.classification(), read.asOf()))
                        .orElse(null),
                altcoinSeason(listed.values(), now).orElse(null),
                openInterest(futures, now).orElse(null),
                List.copyOf(rows));
    }

    private static Map<UUID, Listed> listed(List<CryptoPair> spot, List<CryptoPair> futures, Map<UUID, Coin> byId) {
        Map<UUID, CryptoPair> spotOf = new LinkedHashMap<>();
        Map<UUID, CryptoPair> futuresOf = new LinkedHashMap<>();
        spot.forEach(pair -> spotOf.merge(pair.getBaseCoinId(), pair, (known, next) -> preferred(known, next, byId)));
        futures.forEach(
                pair -> futuresOf.merge(pair.getBaseCoinId(), pair, (known, next) -> preferred(known, next, byId)));
        Map<UUID, Listed> listed = new LinkedHashMap<>();
        Set<UUID> bases = new LinkedHashSet<>(spotOf.keySet());
        bases.addAll(futuresOf.keySet());
        for (UUID base : bases) {
            Coin coin = byId.get(base);
            if (coin == null) {
                continue;
            }
            CryptoPair spotPair = spotOf.get(base);
            CryptoPair futuresPair = futuresOf.get(base);
            CryptoPair series = spotPair != null ? spotPair : futuresPair;
            Coin quote = byId.get(series.getQuoteCoinId());
            listed.put(base, new Listed(coin, spotPair, futuresPair, quote == null ? null : quote.getSymbol()));
        }
        return listed;
    }

    /** Of two pairs of the same coin, the one quoted in USDT, else the first in display order. */
    private static CryptoPair preferred(CryptoPair known, CryptoPair next, Map<UUID, Coin> coins) {
        boolean knownUsdt = isUsdt(known, coins);
        boolean nextUsdt = isUsdt(next, coins);
        if (knownUsdt != nextUsdt) {
            return knownUsdt ? known : next;
        }
        return known;
    }

    private static boolean isUsdt(CryptoPair pair, Map<UUID, Coin> coins) {
        Coin quote = coins.get(pair.getQuoteCoinId());
        return quote != null && QUOTE.equals(quote.getSymbol());
    }

    private static CoinRow row(
            Listed listed, Supply supply, BigDecimal hourAgo, BigDecimal weekAgo, List<BigDecimal> week) {
        Coin coin = listed.coin();
        return new CoinRow(
                coin.getSymbol(),
                coin.getCoinName(),
                coin.getLogoUrl(),
                supply == null ? null : supply.rank(),
                listed.spot() == null ? null : listed.spot().getSymbol(),
                listed.futures() == null ? null : listed.futures().getSymbol(),
                listed.quote(),
                supply == null ? null : supply.circulatingSupply(),
                hourAgo,
                weekAgo,
                week.stream().map(BigDecimal::stripTrailingZeros).toList());
    }

    private Optional<AltcoinSeason> altcoinSeason(Iterable<Listed> listed, Instant now) {
        List<Listed> measured = new ArrayList<>();
        listed.forEach(coin -> {
            if (!PEGGED.contains(coin.coin().getSymbol())) {
                measured.add(coin);
            }
        });
        List<Series> series = measured.stream().map(Listed::series).toList();
        Instant from = now.minus(Duration.ofDays(ALTCOIN_SEASON_DAYS));
        Map<Series, BigDecimal> then =
                figures.closesAt(series, MarketInterval.ONE_DAY, from, from.minus(Duration.ofDays(2)));
        Map<Series, BigDecimal> latestClose =
                figures.closesAt(series, MarketInterval.ONE_DAY, now, now.minus(Duration.ofDays(2)));
        BigDecimal bitcoin = null;
        List<BigDecimal> altcoins = new ArrayList<>();
        for (Listed coin : measured) {
            BigDecimal start = then.get(coin.series());
            BigDecimal end = latestClose.get(coin.series());
            if (start == null || end == null || start.signum() <= 0) {
                continue;
            }
            BigDecimal change = end.divide(start, PERCENT);
            if ("BTC".equals(coin.coin().getSymbol())) {
                bitcoin = change;
            } else {
                altcoins.add(change);
            }
        }
        if (bitcoin == null || altcoins.size() < ALTCOIN_SEASON_MIN) {
            return Optional.empty();
        }
        BigDecimal benchmark = bitcoin;
        long beat = altcoins.stream()
                .filter(change -> change.compareTo(benchmark) > 0)
                .count();
        int index = (int) Math.round(100.0 * beat / altcoins.size());
        return Optional.of(new AltcoinSeason(index, altcoins.size(), ALTCOIN_SEASON_DAYS));
    }

    private Optional<OpenInterest> openInterest(List<CryptoPair> futures, Instant now) {
        List<UUID> ids = futures.stream().map(CryptoPair::getId).toList();
        List<OpenInterestPoint> points =
                figures.openInterestHourly(ids, now.minus(Duration.ofHours(24)), now, Duration.ofMinutes(15));
        if (points.isEmpty()) {
            return Optional.empty();
        }
        OpenInterestPoint first = points.getFirst();
        OpenInterestPoint last = points.getLast();
        BigDecimal change = null;
        if (points.size() > 1
                && first.pairs() == last.pairs()
                && first.valueUsd().signum() > 0
                && !first.at().isAfter(now.minus(Duration.ofHours(23)))) {
            change = last.valueUsd()
                    .subtract(first.valueUsd())
                    .multiply(HUNDRED)
                    .divide(first.valueUsd(), PERCENT)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return Optional.of(new OpenInterest(
                last.valueUsd(),
                change,
                points.stream().map(p -> new Point(p.at(), p.valueUsd())).toList()));
    }

    private static GlobalMarket global(CoinPaprikaGlobal global) {
        return new GlobalMarket(
                global.marketCapUsd(),
                global.marketCapChange24hPercent(),
                global.volume24hUsd(),
                global.bitcoinDominancePercent(),
                global.asOf());
    }
}
