package com.cryptopilot.paper.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.SymbolRules;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.TriggerCondition;
import java.math.BigDecimal;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Each Spot filter a paper order meets, table by table: a value at the boundary passes, one just past it is refused
 * with the filter's code, the field, the value, the limit and which limit it broke. Nothing is rounded.
 *
 * <p>Rule: TR-02, TR-09, TR-11; BR-23, BR-30.
 *
 * <p>Reference: Binance Spot API documentation, "Filters".
 */
class SpotFiltersTest {

    private static final BigDecimal TICK = new BigDecimal("0.01");
    private static final BigDecimal STEP = new BigDecimal("0.001");
    private static final BigDecimal MIN_NOTIONAL = new BigDecimal("5");

    /** BTCUSDT-like rules. */
    private static final SymbolRules RULES = new SymbolRules(
            new BigDecimal("1.00"),
            new BigDecimal("1000"),
            new BigDecimal("0.010"),
            new BigDecimal("20"),
            new BigDecimal("0.02"),
            new BigDecimal("5"),
            new BigDecimal("0.02"),
            null,
            new BigDecimal("1000"),
            true,
            new BigDecimal("1.5"),
            new BigDecimal("0.5"),
            new BigDecimal("2"),
            new BigDecimal("0.8"),
            10,
            500,
            20,
            2000,
            200,
            5);

    private static final PairFilters FILTERS = new PairFilters(TICK, STEP, MIN_NOTIONAL, RULES);

    @ParameterizedTest(name = "price {0}: {1}")
    @CsvSource({
        "1.00, OK,",
        "1000, OK,",
        "90.01, OK,",
        "0.99, MIN, 1",
        "1000.01, MAX, 1000",
        "90.005, STEP, 0.01",
    })
    void PRICE_FILTER_aPrice_withinItsBounds_onTheTickFromMinPrice(String price, String bound, String limit) {
        assertRule(
                () -> SpotFilters.requirePrice(FILTERS, new BigDecimal(price), "price"),
                ErrorCode.PAPER_PRICE_FILTER,
                "price",
                price,
                bound,
                limit);
    }

    @ParameterizedTest(name = "quantity {0}: {1}")
    @CsvSource({
        "0.010, OK,",
        "20, OK,",
        "1.234, OK,",
        "0.009, MIN, 0.01",
        "20.001, MAX, 20",
        "1.2345, STEP, 0.001",
    })
    void LOT_SIZE_aQuantity_withinItsBounds_onTheStepFromMinQty(String quantity, String bound, String limit) {
        assertRule(
                () -> SpotFilters.requireQuantity(FILTERS, new BigDecimal(quantity), "quantity"),
                ErrorCode.PAPER_LOT_SIZE,
                "quantity",
                quantity,
                bound,
                limit);
    }

    @Test
    void LOT_SIZE_aQuantity_beyondTheDecimalsABalanceHolds_isRefused_evenOnAFinerStep() {
        PairFilters fine = new PairFilters(TICK, new BigDecimal("0.000000001"), MIN_NOTIONAL);

        assertRule(
                () -> SpotFilters.requireQuantity(fine, new BigDecimal("0.000000001"), "quantity"),
                ErrorCode.PAPER_LOT_SIZE,
                "quantity",
                "0.000000001",
                "STEP",
                "0.00000001");
        assertThatCode(() -> SpotFilters.requireQuantity(fine, new BigDecimal("0.00000001"), "quantity"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "market quantity {0}: {1}")
    @CsvSource({
        "0.02, OK,",
        "5, OK,",
        "0.01, MIN, 0.02",
        "5.02, MAX, 5",
        "1.03, STEP, 0.02",
    })
    void MARKET_LOT_SIZE_aMarketQuantity_withinItsBounds_onItsStep(String quantity, String bound, String limit) {
        assertRule(
                () -> SpotFilters.requireMarketQuantity(FILTERS, new BigDecimal(quantity), "quantity", true),
                ErrorCode.PAPER_MARKET_LOT_SIZE,
                "quantity",
                quantity,
                bound,
                limit);
    }

    @Test
    void MARKET_LOT_SIZE_aQuantityFromQuoteOrderQty_meetsBothBounds_butNoStep() {
        assertThatCode(() -> SpotFilters.requireMarketQuantity(FILTERS, new BigDecimal("1.031"), "q", false))
                .doesNotThrowAnyException();
        assertRule(
                () -> SpotFilters.requireMarketQuantity(FILTERS, new BigDecimal("0.005"), "q", false),
                ErrorCode.PAPER_LOT_SIZE,
                "q",
                "0.005",
                "MIN",
                "0.01");
        assertRule(
                () -> SpotFilters.requireMarketQuantity(FILTERS, new BigDecimal("5.5"), "q", false),
                ErrorCode.PAPER_MARKET_LOT_SIZE,
                "q",
                "5.5",
                "MAX",
                "5");
        assertRule(
                () -> SpotFilters.requirePositive(FILTERS, BigDecimal.ZERO.setScale(8), "q"),
                ErrorCode.PAPER_LOT_SIZE,
                "q",
                "0",
                "MIN",
                "0.01");
        assertRule(
                () -> SpotFilters.requirePositive(new PairFilters(TICK, STEP, MIN_NOTIONAL), BigDecimal.ZERO, "q"),
                ErrorCode.PAPER_LOT_SIZE,
                "q",
                "0",
                "MIN",
                "0.001");
        assertThatCode(() -> SpotFilters.requirePositive(FILTERS, BigDecimal.ONE, "q"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "notional {0}, market {1}: {2}")
    @CsvSource({
        "5, false, OK,",
        "1000, false, OK,",
        "4.99, false, MIN, 5",
        "1000.01, false, MAX, 1000",
        "4.99, true, MIN, 5",
        "1000.01, true, MAX, 1000",
    })
    void NOTIONAL_anOrdersValue_withinItsBounds(String notional, boolean market, String bound, String limit) {
        assertRule(
                () -> SpotFilters.requireNotional(FILTERS, new BigDecimal(notional), market, "quantity"),
                ErrorCode.PAPER_NOTIONAL,
                "quantity",
                notional,
                bound,
                limit);
    }

    @Test
    void NOTIONAL_aMarketOrder_meetsOnlyTheBoundsThePairAppliesToIt() {
        SymbolRules notApplied = new SymbolRules(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                new BigDecimal("1000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        PairFilters filters = new PairFilters(TICK, STEP, MIN_NOTIONAL, notApplied);

        assertThatCode(() -> SpotFilters.requireNotional(filters, new BigDecimal("1"), true, "q"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SpotFilters.requireNotional(filters, new BigDecimal("2000"), true, "q"))
                .as("applyMaxToMarket not sent: off for MARKET")
                .doesNotThrowAnyException();
        assertRule(
                () -> SpotFilters.requireNotional(filters, new BigDecimal("2000"), false, "q"),
                ErrorCode.PAPER_NOTIONAL,
                "q",
                "2000",
                "MAX",
                "1000");
    }

    @ParameterizedTest(name = "{0} at {1}: {2}")
    @CsvSource({
        "BUY, 150, OK,",
        "BUY, 50, OK,",
        "BUY, 150.01, MAX, 150",
        "BUY, 49.99, MIN, 50",
        "SELL, 200, OK,",
        "SELL, 80, OK,",
        "SELL, 200.01, MAX, 200",
        "SELL, 79.99, MIN, 80",
    })
    void PERCENT_PRICE_BY_SIDE_aLimitPrice_withinTheMultipliersOfItsSide(
            OrderSide side, String price, String bound, String limit) {
        assertRule(
                () -> SpotFilters.requirePercentPrice(FILTERS, side, new BigDecimal(price), new BigDecimal("100"), "p"),
                ErrorCode.PAPER_PERCENT_PRICE,
                "p",
                price,
                bound,
                limit);
    }

    @Test
    void PERCENT_PRICE_BY_SIDE_withoutALastPriceOrRules_checksNothing() {
        assertThatCode(() -> SpotFilters.requirePercentPrice(FILTERS, OrderSide.BUY, BigDecimal.TEN, null, "p"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SpotFilters.requirePercentPrice(
                        new PairFilters(TICK, STEP, MIN_NOTIONAL), OrderSide.SELL, BigDecimal.TEN, BigDecimal.ONE, "p"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} delta {1}: {2}")
    @CsvSource({
        "AT_OR_ABOVE, 10, OK,",
        "AT_OR_ABOVE, 500, OK,",
        "AT_OR_ABOVE, 9, MIN, 10",
        "AT_OR_ABOVE, 501, MAX, 500",
        "AT_OR_BELOW, 20, OK,",
        "AT_OR_BELOW, 2000, OK,",
        "AT_OR_BELOW, 19, MIN, 20",
        "AT_OR_BELOW, 2001, MAX, 2000",
    })
    void TRAILING_DELTA_aDelta_withinTheRangeOfTheSideItsStopTriggersOn(
            TriggerCondition condition, long delta, String bound, String limit) {
        assertRule(
                () -> SpotFilters.requireTrailingDelta(FILTERS, condition, delta, "trailingDelta"),
                ErrorCode.PAPER_TRAILING_DELTA,
                "trailingDelta",
                Long.toString(delta),
                bound,
                limit);
    }

    @Test
    void aPairWithoutRules_checksOnlyTheTickTheStepAndTheMinimumNotional() {
        PairFilters plain = new PairFilters(TICK, STEP, MIN_NOTIONAL);

        assertThatCode(() -> {
                    SpotFilters.requirePrice(plain, new BigDecimal("0.01"), "p");
                    SpotFilters.requireQuantity(plain, new BigDecimal("1000000"), "q");
                    SpotFilters.requireMarketQuantity(plain, new BigDecimal("1000000"), "q", true);
                    SpotFilters.requireNotional(plain, new BigDecimal("1E+12"), false, "q");
                    SpotFilters.requireTrailingDelta(plain, TriggerCondition.AT_OR_ABOVE, 99_999, "d");
                })
                .doesNotThrowAnyException();
        assertRule(
                () -> SpotFilters.requirePrice(plain, new BigDecimal("0.015"), "p"),
                ErrorCode.PAPER_PRICE_FILTER,
                "p",
                "0.015",
                "STEP",
                "0.01");
    }

    /** {@code bound} {@code OK} passes; otherwise the refusal names the field, the value, the limit and the bound. */
    private static void assertRule(
            ThrowingCallable check, ErrorCode code, String field, String value, String bound, String limit) {
        if (bound.equals("OK")) {
            assertThatCode(check).doesNotThrowAnyException();
            return;
        }
        BusinessException refused = catchThrowableOfType(BusinessException.class, check);
        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.errorCode()).isEqualTo(code);
        assertThat(refused.messageArgs())
                .containsExactly(
                        field, new BigDecimal(value).stripTrailingZeros().toPlainString(), limit, bound);
    }
}
