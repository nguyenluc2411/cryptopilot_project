package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
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

    /** Below and above the stop: a STOP never fills at activation; one already triggered is refused before (D-112). */
    @Test
    void D77_onlyARangeWhoseCandleOpenedAtOrAfterTheActivation_mayAct() {
        PriceRange range = new PriceRange(MarketType.SPOT, PAIR, ENTRY, ENTRY, MINUTE, MINUTE.plusSeconds(59));

        assertThat(EntryFillRule.mayAct(entryActivatedAt(MINUTE.minusMillis(1)), range))
                .isTrue();
        assertThat(EntryFillRule.mayAct(entryActivatedAt(MINUTE), range)).isTrue();
        assertThat(EntryFillRule.mayAct(entryActivatedAt(MINUTE.plusMillis(1)), range))
                .isFalse();
        assertThat(EntryFillRule.mayAct(entryActivatedAt(MINUTE.plusSeconds(30)), range))
                .isFalse();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"LIMIT, LONG, BELOW", "LIMIT, SHORT, ABOVE", "STOP, LONG, ABOVE", "STOP, SHORT, BELOW"})
    void BR33_anEntryWaits_onItsBookSide(EntryType entryType, Direction direction, Side side) {
        assertThat(EntryFillRule.entrySide(entryType, direction)).isEqualTo(side);
    }

    @Test
    void BR33_aMarketEntry_hasNoBookSide() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntryFillRule.entrySide(EntryType.MARKET, Direction.LONG));
    }

    private static PriceRange range(String open, String low, String high) {
        return new PriceRange(
                MarketType.SPOT,
                PAIR,
                open == null ? null : new BigDecimal(open),
                new BigDecimal(low),
                new BigDecimal(high),
                MINUTE,
                MINUTE.plusSeconds(10),
                false);
    }

    private static TrackedEntry stopEntry(Direction direction) {
        boolean isLong = direction == Direction.LONG;
        return TrackedEntries.of(
                PLAN,
                MarketType.SPOT,
                PAIR,
                direction,
                EntryType.STOP,
                ENTRY,
                new BigDecimal(isLong ? "90" : "110"),
                new BigDecimal(isLong ? "120" : "80"),
                null,
                MINUTE.minusSeconds(60));
    }

    private static TrackedEntry entryActivatedAt(Instant activatedAt) {
        return TrackedEntries.limit(PLAN, MarketType.SPOT, PAIR, Direction.LONG, ENTRY, activatedAt);
    }
}
