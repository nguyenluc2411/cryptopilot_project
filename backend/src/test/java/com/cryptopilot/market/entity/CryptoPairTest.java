package com.cryptopilot.market.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import java.math.BigDecimal;
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
    @Test
    void NSF01_eachMarket_keepsItsOwnFilters() {
        CryptoPair pair = CryptoPair.register(BTC, USDT, "BTCUSDT");

        pair.applyFilters(MarketType.SPOT, SPOT_FILTERS);
        assertThat(pair.filters(MarketType.FUTURES)).isEmpty();
        pair.applyFilters(MarketType.FUTURES, FUTURES_FILTERS);

        assertThat(pair.filters(MarketType.SPOT)).contains(SPOT_FILTERS);
        assertThat(pair.filters(MarketType.FUTURES)).contains(FUTURES_FILTERS);
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
