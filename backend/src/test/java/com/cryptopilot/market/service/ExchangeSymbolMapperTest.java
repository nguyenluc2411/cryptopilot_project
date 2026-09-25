package com.cryptopilot.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.cryptopilot.market.MarketType;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.client.ExchangeSymbol;
import com.cryptopilot.market.model.ExchangeListing;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * From the T-017 client's {@code ExchangeSymbol} to an {@link ExchangeListing}: every field used, the
 * precision kept, and the symbols the system cannot list left out rather than half-read.
 *
 * <p>The fixtures are real entries of Binance exchange information, read 2026-09-24: BTCUSDT and SHIBUSDT
 * on Spot; BTCUSDT perpetual, its quarterly contract BTCUSDT_260925, and 1000SHIBUSDT on futures.
 *
 * <p>Rule: NSF-01; BR-07, BR-09; TECHNICAL_DESIGN 5.4 and 7.5.
 */
class ExchangeSymbolMapperTest {

    private static final ExchangeSymbol BTC_SPOT = new ExchangeSymbol(
            "BTCUSDT",
            "TRADING",
            "BTC",
            "USDT",
            null,
            new BigDecimal("0.01000000"),
            new BigDecimal("0.00001000"),
            new BigDecimal("0.00001000"),
            new BigDecimal("5.00000000"));

    private static final ExchangeSymbol SHIB_SPOT = new ExchangeSymbol(
            "SHIBUSDT",
            "TRADING",
            "SHIB",
            "USDT",
            null,
            new BigDecimal("0.00000001"),
            new BigDecimal("1.00"),
            new BigDecimal("1.00"),
            new BigDecimal("1.00000000"));

    private static final ExchangeSymbol BTC_PERPETUAL = new ExchangeSymbol(
            "BTCUSDT",
            "TRADING",
            "BTC",
            "USDT",
            "PERPETUAL",
            new BigDecimal("0.10"),
            new BigDecimal("0.001"),
            new BigDecimal("0.001"),
            new BigDecimal("50"));

    private static final ExchangeSymbol BTC_QUARTERLY = new ExchangeSymbol(
            "BTCUSDT_260925",
            "TRADING",
            "BTC",
            "USDT",
            "CURRENT_QUARTER",
            new BigDecimal("0.1"),
            new BigDecimal("0.001"),
            new BigDecimal("0.001"),
            new BigDecimal("5"));

    /** Every field used: market, symbol, both coins, trading status and the three filters. */
    @Test
    void NSF01_aSpotSymbol_mapsEveryFieldItUses() {
        ExchangeListing listing =
                ExchangeSymbolMapper.toListing(MarketType.SPOT, BTC_SPOT).orElseThrow();

        assertThat(listing.market()).isEqualTo(MarketType.SPOT);
        assertThat(listing.symbol()).isEqualTo("BTCUSDT");
        assertThat(listing.baseAsset()).isEqualTo("BTC");
        assertThat(listing.quoteAsset()).isEqualTo("USDT");
        assertThat(listing.trading()).isTrue();
        assertThat(listing.filters())
                .isEqualTo(new PairFilters(
                        new BigDecimal("0.01000000"), new BigDecimal("0.00001000"), new BigDecimal("5.00000000")));
    }

    /** The same symbol on futures is the same pair's other market, with its own, coarser filters. */
    @Test
    void NSF01_thePerpetualContract_mapsToTheFuturesMarketOfTheSameSymbol() {
        ExchangeListing listing = ExchangeSymbolMapper.toListing(MarketType.FUTURES, BTC_PERPETUAL)
                .orElseThrow();

        assertThat(listing.market()).isEqualTo(MarketType.FUTURES);
        assertThat(listing.symbol()).isEqualTo("BTCUSDT");
        assertThat(listing.filters().tickSize()).isEqualTo(new BigDecimal("0.10"));
        assertThat(listing.filters().minNotional()).isEqualTo(new BigDecimal("50"));
    }

    /** TECHNICAL_DESIGN 5.4: the smallest real tick arrives with every digit and its scale. */
    @Test
    void TD54_theSmallestRealTick_isCarriedWithItsPrecision() {
        PairFilters filters = ExchangeSymbolMapper.toListing(MarketType.SPOT, SHIB_SPOT)
                .orElseThrow()
                .filters();

        assertThat(filters.tickSize()).isEqualTo(new BigDecimal("0.00000001"));
        assertThat(filters.tickSize().scale()).isEqualTo(8);
        assertThat(filters.stepSize()).isEqualTo(new BigDecimal("1.00"));
    }

    /**
     * A quarterly contract shares BTC and USDT with the perpetual one; only perpetual futures are in scope,
     * so it is not listed — and neither is a futures symbol with no contract type.
     */
    @Test
    void NSF01_aContractThatIsNotPerpetual_isNotListed() {
        ExchangeSymbol noContractType = new ExchangeSymbol(
                "ETHUSDT",
                "TRADING",
                "ETH",
                "USDT",
                null,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE);

        assertThat(ExchangeSymbolMapper.toListing(MarketType.FUTURES, BTC_QUARTERLY))
                .isEmpty();
        assertThat(ExchangeSymbolMapper.toListing(MarketType.FUTURES, noContractType))
                .isEmpty();
    }

    /** On Spot a contract type means nothing and is not looked at. */
    @Test
    void NSF01_aContractTypeOnSpot_isIgnored() {
        assertThat(ExchangeSymbolMapper.toListing(MarketType.SPOT, BTC_QUARTERLY))
                .isPresent();
    }

    /**
     * An unknown or non-trading status is not dropped: it maps to {@code trading = false}, which is how
     * NSF-01 flags a pair that stopped trading. Dropping it would make the pair silently vanish instead.
     */
    @ParameterizedTest(name = "status {0}")
    @ValueSource(strings = {"BREAK", "HALT", "END_OF_DAY", "PENDING_TRADING", "SOMETHING_NEW"})
    void NSF01_anyStatusButTrading_isListedAsNotTrading(String status) {
        ExchangeSymbol halted = new ExchangeSymbol(
                "BTCUSDT",
                status,
                "BTC",
                "USDT",
                null,
                BTC_SPOT.tickSize(),
                BTC_SPOT.stepSize(),
                BTC_SPOT.minQuantity(),
                BTC_SPOT.minNotional());

        assertThat(ExchangeSymbolMapper.toListing(MarketType.SPOT, halted))
                .hasValueSatisfying(listing -> assertThat(listing.trading()).isFalse());
    }

    /** A symbol whose filters a plan cannot be sized with is not listed: missing, zero, or too precise. */
    @Test
    void TD75_unusableFilters_leaveTheSymbolUnlisted() {
        assertThat(ExchangeSymbolMapper.toListing(MarketType.SPOT, withFilters(null, BigDecimal.ONE, BigDecimal.ONE)))
                .as("no tick")
                .isEmpty();
        assertThat(ExchangeSymbolMapper.toListing(MarketType.SPOT, withFilters(BigDecimal.ONE, null, BigDecimal.ONE)))
                .as("no step")
                .isEmpty();
        assertThat(ExchangeSymbolMapper.toListing(MarketType.SPOT, withFilters(BigDecimal.ONE, BigDecimal.ONE, null)))
                .as("no minimum value")
                .isEmpty();
        assertThat(ExchangeSymbolMapper.toListing(
                        MarketType.SPOT, withFilters(new BigDecimal("0.00000000"), BigDecimal.ONE, BigDecimal.ONE)))
                .as("a zero tick means the rule is not enforced, which a plan cannot round to")
                .isEmpty();
        assertThat(ExchangeSymbolMapper.toListing(
                        MarketType.SPOT,
                        withFilters(new BigDecimal("0.0000000000001"), BigDecimal.ONE, BigDecimal.ONE)))
                .as("more decimals than the column holds")
                .isEmpty();
    }

    @Test
    void NSF01_missingArguments_areRefused() {
        assertThatNullPointerException().isThrownBy(() -> ExchangeSymbolMapper.toListing(null, BTC_SPOT));
        assertThatNullPointerException().isThrownBy(() -> ExchangeSymbolMapper.toListing(MarketType.SPOT, null));
    }

    private static ExchangeSymbol withFilters(BigDecimal tick, BigDecimal step, BigDecimal minNotional) {
        return new ExchangeSymbol("XYZUSDT", "TRADING", "XYZ", "USDT", null, tick, step, BigDecimal.ONE, minNotional);
    }
}
