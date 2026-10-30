package com.cryptopilot.market.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.SymbolRules;
import com.cryptopilot.market.model.enums.ExchangeStatus;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.market.model.enums.PairStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pair and the coin as objects, before any database: what a new pair looks like, that each market
 * keeps its own filters, when a pair counts as enabled, and which symbols are refused.
 *
 * <p>Rule: BR-07; SRS UC-45; NSF-01.
 */
class CryptoPairTest {

    private static final UUID BTC = UUID.fromString("019b76da-a800-7000-8000-0000000000c1");
    private static final UUID USDT = UUID.fromString("019b76da-a800-7000-8000-0000000000c2");

    private static final PairFilters SPOT_FILTERS =
            new PairFilters(new BigDecimal("0.01000000"), new BigDecimal("0.00001000"), new BigDecimal("5.00000000"));
    private static final PairFilters FUTURES_FILTERS =
            new PairFilters(new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("50"));

    /** TR-02: the other Spot filters are stored and read back with the three; futures keeps none of them. */
    @Test
    void TR02_theOtherSpotFilters_areStoredAndReadBack_andFuturesKeepOnlyTheirOwn() {
        SymbolRules rules = new SymbolRules(
                new BigDecimal("0.01"),
                new BigDecimal("1000000"),
                new BigDecimal("0.00001"),
                new BigDecimal("9000"),
                null,
                new BigDecimal("124.19455715"),
                null,
                true,
                new BigDecimal("9000000"),
                false,
                new BigDecimal("1.2"),
                new BigDecimal("0.5"),
                new BigDecimal("2"),
                new BigDecimal("0.8"),
                10,
                2000,
                10,
                2000,
                200,
                5);
        PairFilters spot =
                new PairFilters(SPOT_FILTERS.tickSize(), SPOT_FILTERS.stepSize(), SPOT_FILTERS.minNotional(), rules);
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");

        pair.applyFilters(MarketType.SPOT, spot);
        pair.applyFilters(
                MarketType.FUTURES,
                new PairFilters(
                        FUTURES_FILTERS.tickSize(), FUTURES_FILTERS.stepSize(), FUTURES_FILTERS.minNotional(), rules));

        assertThat(pair.filters(MarketType.SPOT)).contains(spot);
        // Futures keep only their own: the price and quantity bounds, PERCENT_PRICE (the BUY's upper and the SELL's
        // lower
        // multiplier) and the caps; no maximum notional and no trailing delta.
        SymbolRules futures = pair.filters(MarketType.FUTURES).orElseThrow().rules();
        assertThat(futures.minPrice()).isEqualByComparingTo(rules.minPrice());
        assertThat(futures.marketMaxQuantity()).isEqualByComparingTo(rules.marketMaxQuantity());
        assertThat(futures.maxNotional()).isNull();
        assertThat(futures.minTrailingAboveDelta()).isNull();
        assertThat(futures.bidMultiplierUp()).isEqualByComparingTo(rules.bidMultiplierUp());
        assertThat(futures.askMultiplierDown()).isEqualByComparingTo(rules.askMultiplierDown());
        assertThat(futures.maxNumOrders()).isEqualTo(rules.maxNumOrders());
    }

    /** BR-07: a new pair is known and not available — INACTIVE, both markets off, no filters yet. */
    @Test
    void BR07_aNewPair_isInactiveOnBothMarketsWithNoFilters() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");

        assertThat(pair.getPairStatus()).isEqualTo(PairStatus.INACTIVE);
        assertThat(pair.isSpotEnabled()).isFalse();
        assertThat(pair.isFuturesEnabled()).isFalse();
        assertThat(pair.getMaxLeverage()).isNull();
        assertThat(pair.getDisplayOrder()).isZero();
        assertThat(pair.filters(MarketType.SPOT)).isEmpty();
        assertThat(pair.filters(MarketType.FUTURES)).isEmpty();
        assertThat(pair.isEnabledOn(MarketType.SPOT)).isFalse();
        assertThat(pair.getBaseCoinId()).isEqualTo(BTC);
        assertThat(pair.getQuoteCoinId()).isEqualTo(USDT);
    }

    /** One symbol, two markets: each keeps its own filters, and applying one leaves the other untouched. */
    /** BR-07: enabling a market activates the pair on it and leaves the other market off; repeating changes nothing. */
    @Test
    void BR07_enablingAMarket_activatesThePairOnThatMarketOnly() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");

        pair.enable(MarketType.FUTURES);
        pair.enable(MarketType.FUTURES);

        assertThat(pair.isEnabledOn(MarketType.FUTURES)).isTrue();
        assertThat(pair.isEnabledOn(MarketType.SPOT)).isFalse();
        pair.enable(MarketType.SPOT);
        assertThat(pair.isEnabledOn(MarketType.SPOT)).isTrue();
        assertThatNullPointerException().isThrownBy(() -> pair.enable(null));
    }

    @Test
    void NSF01_eachMarket_keepsItsOwnFilters() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");

        pair.applyFilters(MarketType.SPOT, SPOT_FILTERS);
        assertThat(pair.filters(MarketType.FUTURES)).isEmpty();
        pair.applyFilters(MarketType.FUTURES, FUTURES_FILTERS);

        assertThat(pair.filters(MarketType.SPOT)).contains(SPOT_FILTERS);
        assertThat(pair.filters(MarketType.FUTURES)).contains(FUTURES_FILTERS);
    }

    /**
     * TR-39: Futures keep PERCENT_PRICE, one pair of multipliers read back for both sides, and the order caps; the
     * Spot-only rules are not kept for Futures.
     */
    @Test
    void TR39_futuresKeepTheirBounds_percentPrice_andTheOrderCaps() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");
        SymbolRules sent = new SymbolRules(
                new BigDecimal("1"),
                new BigDecimal("4529764"),
                new BigDecimal("0.001"),
                new BigDecimal("1000"),
                new BigDecimal("0.002"),
                new BigDecimal("120"),
                new BigDecimal("0.001"),
                null,
                null,
                null,
                new BigDecimal("1.05"),
                new BigDecimal("0.95"),
                new BigDecimal("1.05"),
                new BigDecimal("0.95"),
                null,
                null,
                null,
                null,
                200,
                10);

        pair.applyFilters(
                MarketType.FUTURES,
                new PairFilters(new BigDecimal("0.1"), new BigDecimal("0.001"), new BigDecimal("5"), sent));

        SymbolRules kept = pair.filters(MarketType.FUTURES).orElseThrow().rules();
        assertThat(kept.minPrice()).isEqualByComparingTo("1");
        assertThat(kept.maxPrice()).isEqualByComparingTo("4529764");
        assertThat(kept.minQuantity()).isEqualByComparingTo("0.001");
        assertThat(kept.maxQuantity()).isEqualByComparingTo("1000");
        assertThat(kept.marketMinQuantity()).isEqualByComparingTo("0.002");
        assertThat(kept.marketMaxQuantity()).isEqualByComparingTo("120");
        assertThat(kept.marketStepSize()).isEqualByComparingTo("0.001");
        assertThat(kept.bidMultiplierUp()).isEqualByComparingTo("1.05");
        assertThat(kept.askMultiplierUp()).isEqualByComparingTo("1.05");
        assertThat(kept.bidMultiplierDown()).isEqualByComparingTo("0.95");
        assertThat(kept.askMultiplierDown()).isEqualByComparingTo("0.95");
        assertThat(kept.maxNumOrders()).isEqualTo(200);
        assertThat(kept.maxNumAlgoOrders()).isEqualTo(10);
    }

    /** A later synchronisation replaces the filters of that market. */
    @Test
    void NSF01_newFilters_replaceTheOldOnes() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");
        pair.applyFilters(MarketType.SPOT, SPOT_FILTERS);
        PairFilters changed = new PairFilters(new BigDecimal("0.1"), new BigDecimal("0.0001"), new BigDecimal("10"));

        pair.applyFilters(MarketType.SPOT, changed);

        assertThat(pair.filters(MarketType.SPOT)).contains(changed);
    }

    /** A pair needs two different coins and a well-formed symbol. */
    @ParameterizedTest(name = "symbol \"{0}\"")
    @ValueSource(strings = {"", " ", "btcusdt", "BTC-USDT", "BTCUSDT_260925", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456"})
    void BR07_aMalformedSymbol_isRefused(String symbol) {
        assertThatIllegalArgumentException().isThrownBy(() -> CryptoPair.register(BTC, USDT, symbol));
    }

    @Test
    void BR07_aPairOfOneCoinWithItself_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CryptoPair.register(BTC, BTC, "BTCBTC"))
                .withMessageContaining("two different coins");
        assertThatNullPointerException().isThrownBy(() -> CryptoPair.register(null, USDT, "BTCUSDT"));
        assertThatNullPointerException().isThrownBy(() -> CryptoPair.register(BTC, null, "BTCUSDT"));
    }

    @Test
    void NSF01_missingArguments_areRefused() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");

        assertThatNullPointerException().isThrownBy(() -> pair.applyFilters(null, SPOT_FILTERS));
        assertThatNullPointerException().isThrownBy(() -> pair.applyFilters(MarketType.SPOT, null));
        assertThatNullPointerException().isThrownBy(() -> pair.filters(null));
        assertThatNullPointerException().isThrownBy(() -> pair.isEnabledOn(null));
    }

    /** Q-15: each market keeps its own normalized status and the exchange's text; a new pair has neither. */
    @Test
    void Q15_eachMarket_keepsItsOwnExchangeStatus() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");
        assertThat(pair.exchangeStatus(MarketType.SPOT)).isNull();
        assertThat(pair.getLastSyncedAt()).isNull();

        pair.recordExchangeStatus(MarketType.SPOT, ExchangeStatus.NOT_TRADING, "BREAK");
        pair.recordExchangeStatus(MarketType.FUTURES, ExchangeStatus.DELISTED, null);
        pair.markSynced(Instant.parse("2026-09-24T00:05:00Z"));

        assertThat(pair.exchangeStatus(MarketType.SPOT)).isEqualTo(ExchangeStatus.NOT_TRADING);
        assertThat(pair.exchangeStatusRaw(MarketType.SPOT)).isEqualTo("BREAK");
        assertThat(pair.exchangeStatus(MarketType.FUTURES)).isEqualTo(ExchangeStatus.DELISTED);
        assertThat(pair.exchangeStatusRaw(MarketType.FUTURES)).isNull();
        assertThat(pair.getLastSyncedAt()).isEqualTo(Instant.parse("2026-09-24T00:05:00Z"));
    }

    @Test
    void Q15_missingArguments_areRefused() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");

        assertThatNullPointerException().isThrownBy(() -> pair.recordExchangeStatus(MarketType.SPOT, null, "TRADING"));
        assertThatNullPointerException()
                .isThrownBy(() -> pair.recordExchangeStatus(null, ExchangeStatus.TRADING, "TRADING"));
        assertThatNullPointerException().isThrownBy(() -> pair.exchangeStatus(null));
        assertThatNullPointerException().isThrownBy(() -> pair.exchangeStatusRaw(null));
        assertThatNullPointerException().isThrownBy(() -> pair.markSynced(null));
    }

    /** A coin arriving from the exchange is named after its symbol until an administrator names it. */
    @Test
    void NSF01_aCoinFromTheExchange_isNamedAfterItsSymbol() {
        Coin coin = Coin.fromExchange("1000SHIB");

        assertThat(coin.getSymbol()).isEqualTo("1000SHIB");
        assertThat(coin.getCoinName()).isEqualTo("1000SHIB");
        assertThat(coin.getLogoUrl()).isNull();
    }

    @ParameterizedTest(name = "coin \"{0}\"")
    @ValueSource(strings = {"", "btc", "B T C", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456"})
    void NSF01_aMalformedCoinSymbol_isRefused(String symbol) {
        assertThatIllegalArgumentException().isThrownBy(() -> Coin.fromExchange(symbol));
    }

    @Test
    void NSF01_aMissingCoinSymbol_isRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> Coin.fromExchange(null));
    }
}
