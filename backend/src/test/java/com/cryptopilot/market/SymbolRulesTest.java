package com.cryptopilot.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The Spot filters beyond the tick, the step and the minimum notional: a zero is off, as the exchange means it, a
 * negative figure or one with more decimals than its column holds is refused, and equal figures are equal whatever
 * their scale.
 *
 * <p>Rule: TR-02; NSF-01.
 */
class SymbolRulesTest {

    @Test
    void TR02_aZeroRule_isOff_andFiguresCompareByValue() {
        SymbolRules exchange = rules(new BigDecimal("0.00000000"), new BigDecimal("5.00000000"), 0, true);
        SymbolRules stored = rules(null, new BigDecimal("5.000000000000"), null, true);

        assertThat(exchange.minPrice()).isNull();
        assertThat(exchange.maxNumOrders()).isNull();
        assertThat(exchange).isEqualTo(stored);
        assertThat(rules(null, new BigDecimal("1E+3"), null, null).maxPrice()).hasToString("1000");
    }

    @Test
    void TR02_theNotionalBounds_applyToMarketOrdersAsTheExchangeDefaultsThem() {
        assertThat(SymbolRules.NONE.minAppliesToMarket()).isTrue();
        assertThat(SymbolRules.NONE.maxAppliesToMarket()).isFalse();
        assertThat(rules(null, null, null, false).minAppliesToMarket()).isFalse();
    }

    @Test
    void TR02_aNegativeRule_orOneWithTooManyDecimals_isRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> rules(new BigDecimal("-1"), null, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rules(new BigDecimal("0.0000000000001"), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> rules(null, null, -1, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new PairFilters(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null));
    }

    private static SymbolRules rules(BigDecimal minPrice, BigDecimal maxPrice, Integer maxNumOrders, Boolean min) {
        return new SymbolRules(
                minPrice,
                maxPrice,
                null,
                null,
                null,
                null,
                null,
                min,
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
                maxNumOrders,
                null);
    }
}
