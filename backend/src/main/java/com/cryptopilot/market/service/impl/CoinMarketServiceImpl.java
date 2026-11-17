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
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.repository.CoinMarketRepository;
import com.cryptopilot.market.repository.CoinMarketRepository.OpenInterestPoint;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * Builds the market list of coins from the listed pairs, the stored candles and open interest, and the reference
 * figures CoinPaprika and alternative.me give.
 *
 * <h2>What a coin is</h2>
 *
 * <p>The base coin of an ACTIVE pair enabled on Spot or Futures (BR-07); a coin that is only ever a quote, such as
 * USDT, has no price of its own here and is left out. Its series is its Spot pair's when it has one, else its Futures
 * pair's; with several, the one quoted in USDT, then the first in display order.
 *
 * <h2>What is computed here</h2>
 *
 * <ul>
 *   <li>The price an hour ago: the close of the 15-minute candle that closed at or before then.
 *   <li>The price seven days ago and the small chart: the hourly closes of the last seven days.
 *   <li>The altcoin season: of the listed coins other than Bitcoin and the stablecoins, the share whose daily close
 *       rose more than Bitcoin's over 90 days. Only meaningful from {@link #ALTCOIN_SEASON_MIN} altcoins on.
 *   <li>The open interest: the latest five-minute sample of every listed Futures pair, summed, once an hour over 24
 *       hours.
 * </ul>
 *
 * <p>The answer is shared: built at most once per {@link #TTL} and handed to every caller in between, because the
 * candles behind it change every fifteen minutes and the page is public.
 *
 * <p>Rule: SRS 3.3.1; UC-09; BR-07, BR-08.
 */
@Service
public class CoinMarketServiceImpl implements CoinMarketService {

    /** How long one answer is served. */
    static final Duration TTL = Duration.ofSeconds(60);

    /** The coins that track a fiat currency, left out of the altcoin season. */
    static final Set<String> STABLECOINS =
            Set.of("USDT", "USDC", "FDUSD", "DAI", "TUSD", "USDE", "USDP", "PYUSD", "BUSD", "USD1");

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
    private volatile CoinMarketResponse cached;

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
        CoinMarketResponse current = cached;
        if (fresh(current)) {
            return current;
        }
        building.lock();
        try {
            current = cached;
            if (fresh(current)) {
                return current;
            }
            current = build(clock.instant());
            cached = current;
            return current;
        } finally {
            building.unlock();
        }
    }

    private boolean fresh(CoinMarketResponse answer) {
        return answer != null && answer.asOf().plus(TTL).isAfter(clock.instant());
    }

    /** One listed coin and its pairs. */
    private record Listed(Coin coin, CryptoPair spot, CryptoPair futures) {

        CryptoPair series() {
            return spot != null ? spot : futures;
        }

        MarketType market() {
            return spot != null ? MarketType.SPOT : MarketType.FUTURES;
        }
    }

    CoinMarketResponse build(Instant now) {
        List<CryptoPair> spot = pairs.findEnabledOnSpot();
        List<CryptoPair> futures = pairs.findEnabledOnFutures();
        Set<UUID> coinIds = new HashSet<>();
        spot.forEach(pair -> {
            coinIds.add(pair.getBaseCoinId());
            coinIds.add(pair.getQuoteCoinId());
        });
        futures.forEach(pair -> {
            coinIds.add(pair.getBaseCoinId());
            coinIds.add(pair.getQuoteCoinId());
        });
        Map<UUID, Coin> byId = new HashMap<>();
        coins.findAllByIdIn(coinIds).forEach(coin -> byId.put(coin.getId(), coin));
        Map<UUID, Listed> listed = new LinkedHashMap<>();
        for (CryptoPair pair : spot) {
            Coin base = byId.get(pair.getBaseCoinId());
            if (base != null) {
                listed.merge(
                        base.getId(),
                        new Listed(base, pair, null),
                        (known, next) -> new Listed(base, preferred(known.spot(), pair, byId), known.futures()));
            }
        }
        for (CryptoPair pair : futures) {
            Coin base = byId.get(pair.getBaseCoinId());
            if (base != null) {
                listed.merge(
                        base.getId(),
                        new Listed(base, null, pair),
                        (known, next) -> new Listed(base, known.spot(), preferred(known.futures(), pair, byId)));
            }
        }

        Map<String, Supply> supplies = reference
                .supplies()
                .map(MarketReferenceStore.Supplies::bySymbol)
                .orElse(Map.of());
        List<CoinRow> rows = new ArrayList<>();
        for (Listed coin : listed.values()) {
            rows.add(row(coin, supplies.get(coin.coin().getSymbol()), now));
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

    /** Of two pairs of the same coin, the one quoted in USDT, else the first in display order. */
    private static CryptoPair preferred(CryptoPair known, CryptoPair next, Map<UUID, Coin> coins) {
        if (known == null) {
            return next;
        }
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

    private CoinRow row(Listed listed, Supply supply, Instant now) {
        CryptoPair series = listed.series();
        MarketType market = listed.market();
        BigDecimal hourAgo = figures.closeAt(
                        series.getId(),
                        market,
                        "15m",
                        now.minus(Duration.ofHours(1)),
                        now.minus(Duration.ofMinutes(90)))
                .orElse(null);
        BigDecimal weekAgo = figures.closeAt(
                        series.getId(),
                        market,
                        "1h",
                        now.minus(Duration.ofDays(7)),
                        now.minus(Duration.ofDays(7).plusHours(3)))
                .orElse(null);
        List<BigDecimal> sparkline = figures.closesSince(series.getId(), market, "1h", now.minus(Duration.ofDays(7)));
        Coin coin = listed.coin();
        return new CoinRow(
                coin.getSymbol(),
                coin.getCoinName(),
                coin.getLogoUrl(),
                supply == null ? null : supply.rank(),
                listed.spot() == null ? null : listed.spot().getSymbol(),
                listed.futures() == null ? null : listed.futures().getSymbol(),
                supply == null ? null : supply.circulatingSupply(),
                hourAgo,
                weekAgo,
                List.copyOf(sparkline));
    }

    private Optional<AltcoinSeason> altcoinSeason(Iterable<Listed> listed, Instant now) {
        Instant from = now.minus(Duration.ofDays(ALTCOIN_SEASON_DAYS));
        BigDecimal bitcoin = null;
        List<BigDecimal> altcoins = new ArrayList<>();
        for (Listed coin : listed) {
            String symbol = coin.coin().getSymbol();
            if (STABLECOINS.contains(symbol)) {
                continue;
            }
            Optional<BigDecimal> change = dailyChange(coin, from, now);
            if (change.isEmpty()) {
                continue;
            }
            if ("BTC".equals(symbol)) {
                bitcoin = change.get();
            } else {
                altcoins.add(change.get());
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

    /** The rise of a coin's daily close from {@code from} to {@code now}, as a ratio, if both closes are stored. */
    private Optional<BigDecimal> dailyChange(Listed coin, Instant from, Instant now) {
        UUID pair = coin.series().getId();
        MarketType market = coin.market();
        Optional<BigDecimal> then = figures.closeAt(pair, market, "1d", from, from.minus(Duration.ofDays(2)));
        Optional<BigDecimal> latest = figures.closeAt(pair, market, "1d", now, now.minus(Duration.ofDays(2)));
        if (then.isEmpty() || latest.isEmpty() || then.get().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(latest.get().divide(then.get(), PERCENT));
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
