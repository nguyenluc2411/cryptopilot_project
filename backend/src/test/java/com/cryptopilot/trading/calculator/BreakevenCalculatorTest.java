package com.cryptopilot.trading.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.FeeSchedule;
import com.cryptopilot.trading.model.FundingFee;
import com.cryptopilot.trading.model.enums.Direction;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * TP-E: the true breakeven stop of an open position. For LONG and SHORT, Spot and Futures, a stop-out at the breakeven
 * price nets zero or more, and a stop one tick closer to the entry nets below zero; the price is on the tick.
 *
 * <p>Rule: BR-36, BR-37, BR-38; D-97, D-122, D-124.
 */
class BreakevenCalculatorTest {

    private static final FeeSchedule SPOT = new FeeSchedule(new BigDecimal("0.001"), new BigDecimal("0.001"), null);
    private static final FeeSchedule FUTURES =
            new FeeSchedule(new BigDecimal("0.0002"), new BigDecimal("0.0005"), new BigDecimal("0.0125"));
    private static final BigDecimal TICK = new BigDecimal("0.01");
    private static final BigDecimal ENTRY = new BigDecimal("100");
    private static final BigDecimal QUANTITY = new BigDecimal("2");

    /**
     * A Spot LONG of 2 at 100 that paid 0.2 to enter, 10 bps of slippage: a stop at 100.31 fills at 100.20 and nets
     * 0.40 − 0.2 − 0.2004 = −0.0004; at 100.32 it fills at 100.21 and nets +0.01958. The bare entry price nets −0.6.
     */
    @Test
    void D124_aSpotLong_breaksEvenAboveItsEntry_byTheFeesAndTheSlippage() {
        BigDecimal breakeven =
                breakeven(MarketType.SPOT, Direction.LONG, "0.2", 10, null).orElseThrow();

        assertThat(breakeven).isEqualByComparingTo("100.32");
        assertThat(net(MarketType.SPOT, Direction.LONG, "0.2", 10, null, "100.32"))
                .isEqualByComparingTo("0.01958");
        assertThat(net(MarketType.SPOT, Direction.LONG, "0.2", 10, null, "100.31"))
                .isEqualByComparingTo("-0.0004");
        assertThat(net(MarketType.SPOT, Direction.LONG, "0.2", 10, null, "100"))
                .as("a stop at the entry price still loses the fees")
                .isNegative();
    }

    @ParameterizedTest(name = "{0} {1}, entry fee {2}, {3} bps, funding {4}")
    @CsvSource({
        "SPOT,    LONG,  0.2,  10, ",
        "SPOT,    LONG,  0.2,   0, ",
        "FUTURES, LONG,  0.1,  10, -0.3",
        "FUTURES, SHORT, 0.1,  10, -0.3",
        "FUTURES, SHORT, 0.04,  5, 0",
        "FUTURES, LONG,  0.1,  10, 1.5",
        "FUTURES, SHORT, 0.1,  10, 1.5"
    })
    void D124_aStopOutAtTheBreakeven_netsZeroOrMore_andOneTickCloserToTheEntry_netsBelowZero(
            MarketType market, Direction direction, String entryFee, int bps, String funding) {
        FundingFee fundingFee = funding == null ? null : FundingFee.complete(new BigDecimal(funding));

        BigDecimal breakeven =
                breakeven(market, direction, entryFee, bps, fundingFee).orElseThrow();

        BigDecimal closer = direction == Direction.LONG ? breakeven.subtract(TICK) : breakeven.add(TICK);
        assertThat(net(market, direction, entryFee, bps, fundingFee, breakeven.toPlainString()))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(net(market, direction, entryFee, bps, fundingFee, closer.toPlainString()))
                .isNegative();
        assertThat(breakeven.remainder(TICK)).as("on the tick").isEqualByComparingTo("0");
    }

    /** Funding paid moves the breakeven further away from the entry; funding received brings it closer. */
    @Test
    void D124_fundingPaid_pushesTheBreakevenAway_fundingReceived_pullsItIn() {
        BigDecimal none = breakeven(MarketType.FUTURES, Direction.LONG, "0.1", 10, FundingFee.complete(BigDecimal.ZERO))
                .orElseThrow();
        BigDecimal paid = breakeven(
                        MarketType.FUTURES, Direction.LONG, "0.1", 10, FundingFee.complete(new BigDecimal("-0.3")))
                .orElseThrow();
        BigDecimal received = breakeven(
                        MarketType.FUTURES, Direction.LONG, "0.1", 10, FundingFee.complete(new BigDecimal("1.5")))
                .orElseThrow();

        assertThat(paid).isGreaterThan(none);
        assertThat(received)
                .as("1.5 received covers the fees, so the stop may sit below the entry")
                .isLessThan(ENTRY);
    }

    /** An incomplete funding is used as known so far; the caller marks the result provisional. */
    @Test
    void D124_anIncompleteFunding_isTheKnownPart() {
        FundingFee partial = FundingFee.partial(new BigDecimal("-0.3"), List.of(Instant.parse("2026-10-25T08:00:00Z")));

        assertThat(breakeven(MarketType.FUTURES, Direction.SHORT, "0.1", 10, partial))
                .isEqualTo(breakeven(
                        MarketType.FUTURES, Direction.SHORT, "0.1", 10, FundingFee.complete(new BigDecimal("-0.3"))));
    }

    /** An entry fee larger than anything a SHORT of 2 at 100 can gain: no positive stop nets zero. */
    @Test
    void D124_aCostNoPriceCovers_hasNoBreakeven() {
        assertThat(breakeven(MarketType.FUTURES, Direction.SHORT, "1000", 10, FundingFee.complete(BigDecimal.ZERO)))
                .isEmpty();
    }

    /** Funding received larger than any loss a LONG can make: every positive stop nets above zero, none is needed. */
    @Test
    void D124_fundingLargerThanAnyLoss_needsNoBreakeven() {
        assertThat(breakeven(MarketType.FUTURES, Direction.LONG, "0.1", 10, FundingFee.complete(new BigDecimal("500"))))
                .isEmpty();
    }

    @Test
    void D124_aTickNotAboveZero_isRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BreakevenCalculator.breakevenStop(
                        MarketType.SPOT,
                        Direction.LONG,
                        LiquidityRole.TAKER,
                        ENTRY,
                        QUANTITY,
                        new BigDecimal("0.2"),
                        SPOT,
                        10,
                        BigDecimal.ZERO,
                        null));
    }

    private static Optional<BigDecimal> breakeven(
            MarketType market, Direction direction, String entryFee, int bps, FundingFee funding) {
        return BreakevenCalculator.breakevenStop(
                market,
                direction,
                LiquidityRole.TAKER,
                ENTRY,
                QUANTITY,
                new BigDecimal(entryFee),
                market == MarketType.SPOT ? SPOT : FUTURES,
                bps,
                TICK,
                funding);
    }

    private static BigDecimal net(
            MarketType market, Direction direction, String entryFee, int bps, FundingFee funding, String stop) {
        return BreakevenCalculator.netAt(
                market,
                direction,
                LiquidityRole.TAKER,
                ENTRY,
                QUANTITY,
                new BigDecimal(entryFee),
                market == MarketType.SPOT ? SPOT : FUTURES,
                bps,
                TICK,
                funding,
                new BigDecimal(stop));
    }
}
