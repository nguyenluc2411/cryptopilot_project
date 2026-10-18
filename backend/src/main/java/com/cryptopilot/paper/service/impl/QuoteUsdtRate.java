package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairCoins;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.UsdtRate;
import com.cryptopilot.paper.model.enums.UsdtRateSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * <p>Rule: TR-13; D-96.
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

    private static final String USDT = PaperAccountServiceImpl.USDT;
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(QuoteUsdtRate.class);

    private final MarketApi market;
    private final Clock clock;

    /** Whether the coin is USDT, which never carries a cost. */
    static boolean isUsdt(CoinListing coin) {
        return USDT.equals(coin.symbol());
    }

    /**
     * The USDT value of one unit of {@code quote} now: 1 for USDT, else the live last price of its USDT pair, else the
     * close of that pair's latest closed 1-minute candle no older than {@link #CANDLE_MAX_AGE}, else none.
     */
    UsdtRate of(CoinListing quote) {
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
        if (candle.isPresent()) {
            return candle.get();
        }
        log.warn("D-96 no USDT rate of {} known; the fill executes and the coin it brings has no known cost", symbol);
        return UsdtRate.NONE;
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
}
