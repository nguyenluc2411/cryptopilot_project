package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.trading.model.InputViolation;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Where an invalidation price may lie, and how far a plan's own deviation limit may go. */
class PlanValidityRuleTest {

    private static final BigDecimal CURRENT = new BigDecimal("100");
    private static final BigDecimal BLOCK = new BigDecimal("0.75");

    /** LIMIT LONG and STOP SHORT: above the current price; LIMIT SHORT and STOP LONG: below it; equal never. */
    @ParameterizedTest(name = "{0} {1}: {2} -> {3}")
    @CsvSource({
        "LIMIT, LONG,  100.01, true",
        "LIMIT, LONG,  100,    false",
        "LIMIT, LONG,  99.99,  false",
        "STOP,  SHORT, 100.01, true",
        "STOP,  SHORT, 100,    false",
        "STOP,  SHORT, 99.99,  false",
        "LIMIT, SHORT, 99.99,  true",
        "LIMIT, SHORT, 100,    false",
        "LIMIT, SHORT, 100.01, false",
        "STOP,  LONG,  99.99,  true",
        "STOP,  LONG,  100,    false",
        "STOP,  LONG,  100.01, false"
    })
    void BR68_theInvalidationPrice_lies_onTheFarSideOfTheCurrentPrice(
            EntryType entryType, Direction direction, String invalidation, boolean accepted) {
        Optional<InputViolation> violation =
                PlanValidityRule.invalidationPrice(entryType, direction, new BigDecimal(invalidation), CURRENT);

        if (accepted) {
            assertThat(violation).isEmpty();
        } else {
            assertThat(violation).hasValueSatisfying(v -> {
                assertThat(v.field()).isEqualTo("invalidationPrice");
                assertThat(v.messageCode()).isEqualTo("MSG16");
                assertThat(v.detail()).contains(direction.name()).contains("current price 100");
            });
        }
    }

    @Test
    void BR68_noInvalidationPrice_isNoViolation_evenWithoutACurrentPrice() {
        assertThat(PlanValidityRule.invalidationPrice(EntryType.LIMIT, Direction.LONG, null, null))
                .isEmpty();
        assertThat(PlanValidityRule.invalidationPrice(EntryType.MARKET, Direction.LONG, null, CURRENT))
                .isEmpty();
    }

    @Test
    void BR68_aMarketPlan_isNeverAskedAboutAnInvalidationPrice() {
        assertThatThrownBy(() ->
                        PlanValidityRule.invalidationPrice(EntryType.MARKET, Direction.LONG, BigDecimal.TEN, CURRENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Equal to the stop counts as triggered on both sides. */
    @ParameterizedTest(name = "{0} stop 100 at last {1} -> refused {2}")
    @CsvSource({
        "LONG,  99.99,  false",
        "LONG,  100,    true",
        "LONG,  100.01, true",
        "SHORT, 100.01, false",
        "SHORT, 100,    true",
        "SHORT, 99.99,  true"
    })
    void BR33_aStopTheLastPriceAlreadyReached_isMsg16OnTheEntryPrice(
            Direction direction, String last, boolean refused) {
        Optional<InputViolation> violation =
                PlanValidityRule.stopAlreadyTriggered(direction, new BigDecimal("100"), new BigDecimal(last));

        if (refused) {
            assertThat(violation).hasValueSatisfying(v -> {
                assertThat(v.field()).isEqualTo("entryPrice");
                assertThat(v.messageCode()).isEqualTo("MSG16");
                assertThat(v.detail()).contains(direction.name()).contains("current price " + last);
            });
        } else {
            assertThat(violation).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0} R with block {1} -> {2}")
    @CsvSource({
        "0,          0.75, false",
        "-0.1,       0.75, false",
        "0.00000001, 0.75, true",
        "0.25,       0.75, true",
        "0.75,       0.75, true",
        "0.75000001, 0.75, false",
        "1,          0.75, false",
        "0.5,        0.5,  true",
        "0.6,        0.5,  false",
        "0.9,        1,    true"
    })
    void BR69_thePlansOwnLimit_isAbove0AndAtMostTheBlockValue(String value, String block, boolean accepted) {
        Optional<InputViolation> violation =
                PlanValidityRule.maxEntryDeviation(new BigDecimal(value), new BigDecimal(block));

        if (accepted) {
            assertThat(violation).isEmpty();
        } else {
            assertThat(violation).hasValueSatisfying(v -> {
                assertThat(v.field()).isEqualTo("maxEntryDeviationR");
                assertThat(v.messageCode()).isEqualTo("MSG15");
            });
        }
    }

    @Test
    void BR69_noLimit_isNoViolation() {
        assertThat(PlanValidityRule.maxEntryDeviation(null, BLOCK)).isEmpty();
    }
}
