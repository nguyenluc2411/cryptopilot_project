package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EntryFillRuleTest {

    private static final UUID PLAN = UUID.fromString("019b76da-a800-7000-8000-0000000000a1");
    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000b001");
    private static final Instant MINUTE = Instant.parse("2026-10-03T08:00:00Z");
    private static final BigDecimal ENTRY = new BigDecimal("100");

    @ParameterizedTest
    @CsvSource({
        "LONG, 99.99, true",
        "LONG, 100, true",
        "LONG, 100.00, true",
        "LONG, 100.01, false",
        "SHORT, 100.01, true",
        "SHORT, 100, true",
        "SHORT, 99.99, false"
    })
    void BR33_aLimitEntry_isReachedAtOrBeyondItsPrice_boundIncluded(
            Direction direction, String price, boolean reached) {
        assertThat(EntryFillRule.reaches(direction, new BigDecimal(price), ENTRY))
                .isEqualTo(reached);
    }

    @Test
    void BR33_aMarketEntry_fillsAtActivationAtTheLastPrice_whateverItsDirection() {
        Optional<BigDecimal> last = Optional.of(new BigDecimal("123.45"));

        assertThat(EntryFillRule.atActivation(EntryType.MARKET, Direction.LONG, ENTRY, last))
                .isEqualTo(last);
        assertThat(EntryFillRule.atActivation(EntryType.MARKET, Direction.SHORT, ENTRY, last))
                .isEqualTo(last);
    }

    @Test
    void BR33_aMarketEntry_withoutALastPrice_isAProgrammingError() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> EntryFillRule.atActivation(EntryType.MARKET, Direction.LONG, ENTRY, Optional.empty()));
    }

    @ParameterizedTest
    @CsvSource({
        "LONG, 99, true",
        "LONG, 100, true",
        "LONG, 101, false",
        "SHORT, 101, true",
        "SHORT, 100, true",
        "SHORT, 99, false"
    })
    void BR33_aLimitEntryAlreadyReachedAtActivation_fillsAtTheLastPrice_otherwiseItWaits(
            Direction direction, String last, boolean fills) {
        Optional<BigDecimal> lastPrice = Optional.of(new BigDecimal(last));

        Optional<BigDecimal> fill = EntryFillRule.atActivation(EntryType.LIMIT, direction, ENTRY, lastPrice);

        assertThat(fill).isEqualTo(fills ? lastPrice : Optional.empty());
    }

    @Test
    void BR33_aLimitEntryWithoutALastPrice_waits() {
        assertThat(EntryFillRule.atActivation(EntryType.LIMIT, Direction.LONG, ENTRY, Optional.empty()))
                .isEmpty();
    }

    @Test
    void D77_onlyARangeWhoseCandleOpenedAtOrAfterTheActivation_mayFill() {
        PriceRange range = new PriceRange(MarketType.SPOT, PAIR, ENTRY, ENTRY, MINUTE, MINUTE.plusSeconds(59));

        assertThat(EntryFillRule.mayFill(entryActivatedAt(MINUTE.minusMillis(1)), range))
                .isTrue();
        assertThat(EntryFillRule.mayFill(entryActivatedAt(MINUTE), range)).isTrue();
        assertThat(EntryFillRule.mayFill(entryActivatedAt(MINUTE.plusMillis(1)), range))
                .isFalse();
        assertThat(EntryFillRule.mayFill(entryActivatedAt(MINUTE.plusSeconds(30)), range))
                .isFalse();
    }

    @Test
    void BR33_D78_aRangeFill_isAtTheEntryPrice_andTheOpenTimeOfTheCandle() {
        PriceRange gapped = new PriceRange(
                MarketType.SPOT, PAIR, new BigDecimal("80"), new BigDecimal("90"), MINUTE, MINUTE.plusSeconds(42));

        assertThat(EntryFillRule.byRange(entryActivatedAt(MINUTE.minusSeconds(60)), gapped))
                .isEqualTo(new Fill(PLAN, ENTRY, MINUTE));
    }

    private static TrackedEntry entryActivatedAt(Instant activatedAt) {
        return new TrackedEntry(PLAN, MarketType.SPOT, PAIR, Direction.LONG, ENTRY, activatedAt);
    }
}
