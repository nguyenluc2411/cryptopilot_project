package com.cryptopilot.trading.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The excursions of an open position: the TD 7.8 worked examples (T-044 cases (a) and (b)), the entry candle and the
 * exit candle.
 *
 * <p>Rule: BR-70; D-111 M4; D-112 R5; TECHNICAL_DESIGN 7.8.
 */
class ExcursionTest {

    private static final Instant MINUTE = Instant.parse("2026-10-03T08:00:00Z");

    /** TD 7.8, LONG case (a): lowest 97 after the fill, 116 in the exit candle clamped to the take profit 115. */
    @Test
    void D112R5_longCaseA_lowest97_highestClampedTo115() {
        Excursion excursion = Excursion.at(new BigDecimal("100"))
                .with(Direction.LONG, range("97", "105"), false)
                .withExit(Direction.LONG, range("110", "116"), false, CloseReason.TAKE_PROFIT, new BigDecimal("115"));

        assertThat(excursion).isEqualTo(new Excursion(new BigDecimal("97"), new BigDecimal("115")));
    }

    /** TD 7.8, SHORT case (b): highest 106 in the exit candle clamped to the stop loss 105, lowest 98. */
    @Test
    void D112R5_shortCaseB_highestClampedTo105_lowest98() {
        Excursion excursion = Excursion.at(new BigDecimal("100"))
                .with(Direction.SHORT, range("98", "103"), false)
                .withExit(Direction.SHORT, range("101", "106"), false, CloseReason.STOP_LOSS, new BigDecimal("105"));

        assertThat(excursion).isEqualTo(new Excursion(new BigDecimal("105"), new BigDecimal("98")));
    }

    /** D-112 R5: the entry candle adds only its adverse extreme, also when the entry filled at its open. */
    @Test
    void D112R5_theEntryCandle_addsOnlyItsAdverseExtreme() {
        assertThat(Excursion.at(new BigDecimal("100")).with(Direction.LONG, range("96", "110"), true))
                .isEqualTo(new Excursion(new BigDecimal("96"), new BigDecimal("100")));
        assertThat(Excursion.at(new BigDecimal("100")).with(Direction.SHORT, range("90", "104"), true))
                .isEqualTo(new Excursion(new BigDecimal("104"), new BigDecimal("100")));
    }

    /** The exit candle clamps the side of the exit: a long stop caps the adverse low, a short take profit the high. */
    @Test
    void D112R5_theExitCandle_clampsTheSideOfTheExit() {
        assertThat(Excursion.at(new BigDecimal("100"))
                        .withExit(
                                Direction.LONG, range("94", "102"), false, CloseReason.STOP_LOSS, new BigDecimal("95")))
                .isEqualTo(new Excursion(new BigDecimal("95"), new BigDecimal("102")));
        assertThat(Excursion.at(new BigDecimal("100"))
                        .withExit(
                                Direction.SHORT,
                                range("84", "103"),
                                false,
                                CloseReason.TAKE_PROFIT,
                                new BigDecimal("85")))
                .isEqualTo(new Excursion(new BigDecimal("103"), new BigDecimal("85")));
        // An exit price outside the candle (a liquidation at its price) never inverts the candle.
        assertThat(Excursion.at(new BigDecimal("100"))
                        .withExit(
                                Direction.LONG,
                                range("92", "99"),
                                false,
                                CloseReason.LIQUIDATION,
                                new BigDecimal("90.37")))
                .isEqualTo(new Excursion(new BigDecimal("92"), new BigDecimal("100")));
    }

    private static PriceRange range(String low, String high) {
        return new PriceRange(
                MarketType.FUTURES,
                UUID.randomUUID(),
                null,
                new BigDecimal(low),
                new BigDecimal(high),
                MINUTE,
                MINUTE.plusSeconds(1),
                false);
    }
}
