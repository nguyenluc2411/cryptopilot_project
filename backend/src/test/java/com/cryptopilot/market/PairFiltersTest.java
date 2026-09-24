package com.cryptopilot.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.math.MathContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The rounding helpers against real exchange filters — the acceptance criterion of T-018.
 *
 * <p>The filter values are the ones Binance published for these symbols when this test was written (Spot
 * {@code GET /api/v3/exchangeInfo?symbol=…} and USDⓈ-M futures {@code GET /fapi/v1/exchangeInfo}, read
 * 2026-09-24): a quantity floored to the step size (BR-23) and a trader's price rounded to
 * the nearest tick (BR-30), on a large-price pair, a sub-cent pair and a futures contract whose steps are
 * coarser than Spot's.
 *
 * <p>Rule: BR-23, BR-25, BR-30; TECHNICAL_DESIGN 5.4 and 7.5.
 */
class PairFiltersTest {

    /** BTCUSDT on Spot: tick 0.01, step 0.00001, minimum value 5. */
    private static final PairFilters BTC_SPOT =
            new PairFilters(new BigDecimal("0.01000000"), new BigDecimal("0.00001000"), new BigDecimal("5.00000000"));

    /** BTCUSDT on futures: tick 0.10, step 0.001, minimum value 50. */
    private static final PairFilters BTC_FUTURES =
            new PairFilters(new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("50"));

    /** SHIBUSDT on Spot: tick 0.00000001, whole-coin steps, minimum value 1. */
    private static final PairFilters SHIB_SPOT =
            new PairFilters(new BigDecimal("0.00000001"), new BigDecimal("1.00"), new BigDecimal("1.00000000"));

    /** BR-23: floored to the step, never rounded up, on three real step sizes. */
    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource({
        "BTC_SPOT,    0.0247899, 0.02478",
        "BTC_SPOT,    0.00000999, 0.00000",
        "BTC_FUTURES, 1.23456,   1.234",
        "BTC_FUTURES, 0.0009,    0.000",
        "SHIB_SPOT,   123456.99, 123456",
        "SHIB_SPOT,   123456,    123456"
    })
    void BR23_aQuantity_isFlooredToTheRealStepSize(String pair, String quantity, String expected) {
        assertThat(filtersOf(pair).floorQuantity(new BigDecimal(quantity))).isEqualByComparingTo(expected);
    }

    /**
     * The worked example of TECHNICAL_DESIGN 11.4 — capital 1,000, risk 1.5 %, entry 27,123.45, stop
     * 26,500, so 15 / 623.45 = 0.024059… — gives 0.024 on a 0.001 step, BTCUSDT futures' real one, and
     * 0.02405 on BTCUSDT Spot's finer 0.00001 step.
     */
    @Test
    void BR23_theDesignExample_floorsOnRealStepSizes() {
        BigDecimal qtyRaw = new BigDecimal("15").divide(new BigDecimal("623.45"), MathContext.DECIMAL128);

        assertThat(BTC_FUTURES.floorQuantity(qtyRaw)).isEqualByComparingTo("0.024");
        assertThat(BTC_SPOT.floorQuantity(qtyRaw)).isEqualByComparingTo("0.02405");
    }

    /** BR-30: a trader's price rounded to the nearest tick, halves up, on three real tick sizes. */
    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource({
        "BTC_SPOT,    27123.455, 27123.46",
        "BTC_SPOT,    27123.454, 27123.45",
        "BTC_FUTURES, 27123.45,  27123.5",
        "BTC_FUTURES, 27123.449, 27123.4",
        "SHIB_SPOT,   0.000012345, 0.00001235",
        "SHIB_SPOT,   0.000012344, 0.00001234"
    })
    void BR30_aPrice_isRoundedToTheNearestRealTick(String pair, String price, String expected) {
        assertThat(filtersOf(pair).roundPrice(new BigDecimal(price))).isEqualByComparingTo(expected);
    }

    /** TECHNICAL_DESIGN 7.5: below the minimum value is refused, the minimum itself is not. */
    @Test
    void BR25_theMinimumNotional_isInclusive() {
        assertThat(BTC_FUTURES.isBelowMinNotional(new BigDecimal("49.99"))).isTrue();
        assertThat(BTC_FUTURES.isBelowMinNotional(new BigDecimal("50.00"))).isFalse();
        assertThat(BTC_FUTURES.isBelowMinNotional(new BigDecimal("50.01"))).isFalse();
    }

    /** The smallest real tick keeps every digit: 0.00000001 is not rounded away. */
    @Test
    void TD54_theSmallestRealTick_keepsItsPrecision() {
        assertThat(SHIB_SPOT.tickSize()).isEqualTo(new BigDecimal("0.00000001"));
        assertThat(SHIB_SPOT.roundPrice(new BigDecimal("0.00000001"))).isEqualByComparingTo("0.00000001");
    }

    /** A filter that is zero, negative or missing is not a rule: construction refuses it. */
    @ParameterizedTest(name = "tick {0}, step {1}, minNotional {2}")
    @CsvSource({"0, 0.001, 5", "0.01, 0, 5", "0.01, 0.001, 0", "-0.01, 0.001, 5"})
    void TD75_aNonPositiveFilter_isRefused(String tick, String step, String minNotional) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new PairFilters(new BigDecimal(tick), new BigDecimal(step), new BigDecimal(minNotional)))
                .withMessageContaining("must be positive");
    }

    /** More decimals than the column holds would be rounded by the database; construction refuses it. */
    @Test
    void TD54_moreDecimalsThanTheColumnHolds_areRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PairFilters(new BigDecimal("0.0000000000001"), BigDecimal.ONE, BigDecimal.TEN))
                .withMessageContaining("tickSize has more than 12 decimals");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PairFilters(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.000000001")))
                .withMessageContaining("minNotional has more than 8 decimals");
        assertThat(new PairFilters(new BigDecimal("0.000000000001"), BigDecimal.ONE, new BigDecimal("0.00000001")))
                .as("exactly the column's scale is accepted, trailing zeros beyond it too")
                .isNotNull();
        assertThat(new PairFilters(new BigDecimal("0.01000000000000"), BigDecimal.ONE, BigDecimal.ONE))
                .isNotNull();
    }

    @Test
    void TD75_missingArguments_areRefused() {
        assertThatNullPointerException().isThrownBy(() -> new PairFilters(null, BigDecimal.ONE, BigDecimal.ONE));
        assertThatNullPointerException().isThrownBy(() -> BTC_SPOT.isBelowMinNotional(null));
    }

    private static PairFilters filtersOf(String name) {
        return switch (name) {
            case "BTC_SPOT" -> BTC_SPOT;
            case "BTC_FUTURES" -> BTC_FUTURES;
            case "SHIB_SPOT" -> SHIB_SPOT;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
