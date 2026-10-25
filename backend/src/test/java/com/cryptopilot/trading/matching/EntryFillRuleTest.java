package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.matching.PriceLevelBook.Side;
import com.cryptopilot.trading.model.Fill;
import com.cryptopilot.trading.model.PriceRange;
import com.cryptopilot.trading.model.TrackedEntries;
import com.cryptopilot.trading.model.TrackedEntry;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.EntryType;
import com.cryptopilot.trading.model.enums.LiquidityRole;
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

    /** Below and above the stop: a STOP never fills at activation; one already triggered is refused before (D-112). */
    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({"LONG, 95", "LONG, 105", "SHORT, 95", "SHORT, 105"})
    void BR33_aStopEntry_isNeverAskedAtActivation(Direction direction, String last) {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        EntryFillRule.atActivation(EntryType.STOP, direction, ENTRY, Optional.of(new BigDecimal(last))))
                .withMessageContaining("STOP");
    }

    @Test
    void BR33_marketAndLimit_atActivation_areUnchangedByTheStopGuard() {
        Optional<BigDecimal> last = Optional.of(new BigDecimal("99"));

        assertThat(EntryFillRule.atActivation(EntryType.MARKET, Direction.LONG, null, last))
                .isEqualTo(last);
        assertThat(EntryFillRule.atActivation(EntryType.LIMIT, Direction.LONG, ENTRY, last))
                .isEqualTo(last);
        assertThat(EntryFillRule.atActivation(EntryType.LIMIT, Direction.SHORT, ENTRY, last))
                .isEmpty();
    }

    @Test
    void BR33_aLimitEntryWithoutALastPrice_waits() {
        assertThat(EntryFillRule.atActivation(EntryType.LIMIT, Direction.LONG, ENTRY, Optional.empty()))
                .isEmpty();
    }

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

    @Test
    void BR33_D78_D111E7_aRestingLimit_fillsAsMakerAtItsOwnPrice_alsoThroughAGap_atTheOpenTimeOfTheCandle() {
        PriceRange gapped = new PriceRange(
                MarketType.SPOT,
                PAIR,
                new BigDecimal("88"),
                new BigDecimal("80"),
                new BigDecimal("90"),
                MINUTE,
                MINUTE.plusSeconds(42),
                false);
        TrackedEntry entry = entryActivatedAt(MINUTE.minusSeconds(60));

        assertThat(EntryFillRule.byRange(entry, gapped)).isEqualTo(new Fill(entry, LiquidityRole.MAKER, ENTRY, MINUTE));
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

    /** A STOP LONG triggers when the high is at or above its stop, a STOP SHORT when the low is at or below it. */
    @ParameterizedTest(name = "{0} stop 100, range {1}..{2}: {3}")
    @CsvSource({
        "LONG, 95, 99.99, false",
        "LONG, 95, 100, true",
        "LONG, 95, 101, true",
        "SHORT, 100.01, 105, false",
        "SHORT, 100, 105, true",
        "SHORT, 99, 105, true"
    })
    void BR33_aStopEntry_triggersAtOrThroughItsStopPrice(Direction direction, String low, String high, boolean hit) {
        TrackedEntry stop = stopEntry(direction);

        assertThat(EntryFillRule.entryReached(stop, range(null, low, high))).isEqualTo(hit);
    }

    /** Opened short of the stop, or with an unknown open: the stop price; opened at or past it: the open. */
    @ParameterizedTest(name = "{0} stop 100, open {1}: fills at {4}")
    @CsvSource({
        "LONG, 99.5, 99, 101, 100",
        "LONG, 100, 100, 101, 100",
        "LONG, 102, 101.5, 103, 102",
        "LONG, , 99, 101, 100",
        "SHORT, 100.5, 99, 101, 100",
        "SHORT, 97, 96, 98, 97",
        "SHORT, , 99, 101, 100"
    })
    void BR33_D112R6_aTriggeredStop_isTaker_atItsStopPrice_orAtTheOpenOfAGap(
            Direction direction, String open, String low, String high, String price) {
        TrackedEntry stop = stopEntry(direction);

        Fill fill = EntryFillRule.byRange(stop, range(open, low, high));

        assertThat(fill.liquidity()).isEqualTo(LiquidityRole.TAKER);
        assertThat(fill.price()).isEqualByComparingTo(price);
        assertThat(fill.executedAt()).isEqualTo(MINUTE);
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
