package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.MinuteKline;
import com.cryptopilot.market.MinuteKlineBatch;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.UsdtRate;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.UsdtRateSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Values a Spot fill's quote coin in USDT, so the cost of every coin stays in USDT whatever the pair is quoted in, and
 * tells which pairs paper trading accepts: those whose base and quote can both be valued in USDT.
 *
 * <p>A replayed fill traded in the past, so it is valued at the close of the {@code QUOTEUSDT} 1-minute candle of the
 * minute it traded in, fetched from the exchange through the market module and kept for the rest of the replay; the
 * rate of now only when that candle cannot be had.
 *
 * <p>Rule: TR-13; D-96; PT-R19.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>, "Money" (an amount is
 * always carried with its currency). Binance FAQ, "How to View or Reset the Average Cost in My Binance Wallet" (the
 * average cost takes the market price when the amount increases).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class QuoteUsdtRate {

    /** The oldest closed 1-minute candle whose close still values a coin. */
    static final Duration CANDLE_MAX_AGE = Duration.ofMinutes(15);

    /** How many fetched closes are kept: a day of minutes for a few quote coins. */
    static final int CACHED_CLOSES = 10_000;

    private static final String USDT = PaperAccountServiceImpl.USDT;
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(QuoteUsdtRate.class);

    private final MarketApi market;
    private final Clock clock;

    private final Map<Minute, BigDecimal> closes = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Minute, BigDecimal> eldest) {
            return size() > CACHED_CLOSES;
        }
    });

    /** Whether the coin is USDT, which never carries a cost. */
    static boolean isUsdt(CoinListing coin) {
        return USDT.equals(coin.symbol());
    }

    /**
     * The USDT value of one unit of {@code quote} now: 1 for USDT, else the live last price of its USDT pair, else the
     * close of that pair's latest closed 1-minute candle no older than {@link #CANDLE_MAX_AGE}, else none.
     */
    UsdtRate of(CoinListing quote) {
        UsdtRate rate = current(quote);
        if (!rate.known()) {
            log.warn(
                    "D-96 no USDT rate of {} known; the fill executes and the coin it brings has no known cost",
                    quote.symbol() + USDT);
        }
        return rate;
    }

    /**
     * The USDT value of one unit of {@code quote} for a fill: a replayed one at the close of its USDT pair's 1-minute
     * candle of the minute it traded in, else as {@link #of(CoinListing)}.
     */
    UsdtRate of(CoinListing quote, FillSource source, Instant tradedAt) {
        if (source == FillSource.REPLAY && !isUsdt(quote)) {
            Optional<BigDecimal> close = closeAt(quote.symbol() + USDT, tradedAt);
            if (close.isPresent()) {
                return new UsdtRate(close.get(), UsdtRateSource.CANDLE_1M);
            }
        }
        return of(quote);
    }

    /** As {@link #of(CoinListing)}, without the warning: for a coin valued outside a fill, such as the grant. */
    UsdtRate current(CoinListing quote) {
        if (isUsdt(quote)) {
            return UsdtRate.ONE;
        }
        String symbol = quote.symbol() + USDT;
        Optional<UsdtRate> live =
                market.currentLastPrice(MarketType.SPOT, symbol).map(price -> new UsdtRate(price, UsdtRateSource.LIVE));
        if (live.isPresent()) {
            return live.get();
        }
        Instant oldest = clock.instant().minus(CANDLE_MAX_AGE);
        Optional<UsdtRate> candle = market.pairListing(symbol)
                .flatMap(pair -> market.latestClosedMinuteKline(MarketType.SPOT, pair.pairId()))
                .filter(kline -> !kline.openTime().plus(MINUTE).isBefore(oldest))
                .map(kline -> new UsdtRate(kline.close(), UsdtRateSource.CANDLE_1M));
        return candle.orElse(UsdtRate.NONE);
    }

    /**
     * The close of the symbol's closed 1-minute candle that covers {@code at}: kept from an earlier fetch, or fetched
     * with the candles after it; empty when the exchange refuses, fails or has none.
     */
    private Optional<BigDecimal> closeAt(String symbol, Instant at) {
        Instant minute = at.truncatedTo(ChronoUnit.MINUTES);
        BigDecimal kept = closes.get(new Minute(symbol, minute));
        if (kept != null) {
            return Optional.of(kept);
        }
        try {
            Optional<MinuteKlineBatch> batch = market.pairListing(symbol)
                    .map(pair -> market.closedMinuteKlines(MarketType.SPOT, pair.pairId(), minute));
            if (batch.isEmpty() || batch.get().refused()) {
                return Optional.empty();
            }
            for (MinuteKline kline : batch.get().klines()) {
                closes.put(new Minute(symbol, kline.openTime()), kline.close());
            }
        } catch (RuntimeException failure) {
            log.warn("PT-R19 1m candle of {} at {} not fetched: {}", symbol, minute, failure.toString());
            return Optional.empty();
        }
        return Optional.ofNullable(closes.get(new Minute(symbol, minute)));
    }

    /**
     * Refuses a pair whose base or quote is neither USDT nor the base of an ACTIVE Spot {@code XXXUSDT} pair, so every
     * coin a Trader can hold has a price in USDT.
     *
     * @throws BusinessException {@code PAPER_PAIR_NOT_VALUED} naming the coin that has none
     */
    void requireValued(PairCoins coins) {
        // A USDT pair is itself the USDT pair of its base.
        if (isUsdt(coins.quote())) {
            return;
        }
        for (CoinListing coin : List.of(coins.base(), coins.quote())) {
            boolean valued = isUsdt(coin)
                    || market.pairListing(coin.symbol() + USDT)
                            .filter(PairListing::spotListed)
                            .isPresent();
            if (!valued) {
                throw new BusinessException(
                        ErrorCode.PAPER_PAIR_NOT_VALUED,
                        "pair " + coins.pairId() + " holds " + coin.symbol() + ", which has no active Spot USDT pair",
                        coin.symbol());
            }
        }
    }

    private record Minute(String symbol, Instant openTime) {}
}
