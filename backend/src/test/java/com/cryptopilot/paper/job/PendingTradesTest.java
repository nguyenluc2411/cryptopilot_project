package com.cryptopilot.paper.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.market.MarketTrade;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.PriceRange;
import com.cryptopilot.paper.model.PriceRange.Print;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The trades of a pair gathered for the matching consumer: appending stays cheap however many wait, the range they are
 * drained into is the one the trades merged one by one give, and past the bound no price extreme is lost.
 */
class PendingTradesTest {

    private static final UUID PAIR = UUID.fromString("019b76da-a800-7000-8000-00000000c002");
    private static final Instant START = Instant.parse("2026-10-23T08:00:00Z");

    /** TR-05: a backlog of 100 000 trades is gathered and read in well under the time a quadratic merge takes. */
    @Test
    void TR05_appendingAHundredThousandTrades_staysLinear() {
        PendingTrades pending = new PendingTrades(MarketType.SPOT, PAIR, 200_000);
        long began = System.nanoTime();

        for (int i = 0; i < 100_000; i++) {
            pending.add(print(String.valueOf(100 + i % 50), START.plusMillis(i), i % 2 == 0));
        }
        PriceRange range = pending.drain();

        Duration took = Duration.ofNanos(System.nanoTime() - began);
        assertThat(took).isLessThan(Duration.ofSeconds(2));
        assertThat(range.prints()).hasSize(100_000);
        assertThat(range.low()).isEqualByComparingTo("100");
        assertThat(range.high()).isEqualByComparingTo("149");
    }

    /**
     * D-77, Q-T8: in order and late trades mixed, the drained range has the low, the high, their times, the volumes
     * and the trades one by one that merging them one after another gives.
     */
    @Test
    void D77_theDrainedRange_isTheOneTheTradesMergedOneByOneGive() {
        List<Print> arriving = List.of(
                print("100", START.plusMillis(10), true),
                print("98", START.plusMillis(20), false),
                print("103", START.plusMillis(40), true),
                // Late: older than the last one.
                print("98", START.plusMillis(15), true),
                print("103", START.plusMillis(30), false),
                print("101", START.plusMillis(50), true),
                print("97.5", START.plusMillis(45), false),
                print("97.5", START.plusMillis(60), true));
        PendingTrades pending = new PendingTrades(MarketType.SPOT, PAIR, 1000);
        PriceRange merged = null;
        for (Print print : arriving) {
            pending.add(print);
            PriceRange one = PriceRange.of(new MarketTrade(
                    MarketType.SPOT,
                    PAIR,
                    1,
                    print.price(),
                    print.quantity(),
                    print.at(),
                    print.takerSold(),
                    print.at()));
            merged = merged == null ? one : merged.withTrades(one);
        }

        PriceRange drained = pending.drain();

        assertThat(drained.low()).isEqualByComparingTo(merged.low());
        assertThat(drained.high()).isEqualByComparingTo(merged.high());
        assertThat(drained.lowAt()).isEqualTo(merged.lowAt()).isEqualTo(START.plusMillis(45));
        assertThat(drained.highAt()).isEqualTo(merged.highAt()).isEqualTo(START.plusMillis(30));
        assertThat(drained.from()).isEqualTo(merged.from()).isEqualTo(START.plusMillis(10));
        assertThat(drained.soldAt()).isEqualTo(merged.soldAt());
        assertThat(drained.boughtAt()).isEqualTo(merged.boughtAt());
        assertThat(drained.prints()).isEqualTo(merged.prints());
        assertThat(drained.prints()).extracting(Print::at).isSorted();
    }

    /** TR-05: past the bound the oldest trades fold into the extremes and volumes; the low and the high stay trades. */
    @Test
    void TR05_pastTheBound_theOldestFold_andNoExtremeIsLost() {
        PendingTrades pending = new PendingTrades(MarketType.SPOT, PAIR, 3);
        boolean folded = false;

        folded |= pending.add(print("100", START.plusMillis(1), true));
        folded |= pending.add(print("90", START.plusMillis(2), true));
        folded |= pending.add(print("120", START.plusMillis(3), false));
        assertThat(folded).isFalse();
        for (int i = 4; i < 10; i++) {
            folded |= pending.add(print("105", START.plusMillis(i), i % 2 == 0));
        }
        PriceRange drained = pending.drain();

        assertThat(folded).isTrue();
        assertThat(pending.size()).isEqualTo(3);
        assertThat(pending.folded()).isEqualTo(6);
        assertThat(drained.low()).isEqualByComparingTo("90");
        assertThat(drained.lowAt()).isEqualTo(START.plusMillis(2));
        assertThat(drained.high()).isEqualByComparingTo("120");
        assertThat(drained.highAt()).isEqualTo(START.plusMillis(3));
        assertThat(drained.from()).isEqualTo(START.plusMillis(1));
        // The folded trades still count in the volumes.
        assertThat(drained.soldAt().get(new BigDecimal("100"))).isEqualByComparingTo("1");
        assertThat(drained.soldAt().get(new BigDecimal("105"))).isEqualByComparingTo("3");
        assertThat(drained.boughtAt().get(new BigDecimal("105"))).isEqualByComparingTo("3");
        assertThat(drained.prints())
                .extracting(Print::price)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("90"),
                        new BigDecimal("120"),
                        new BigDecimal("105"),
                        new BigDecimal("105"),
                        new BigDecimal("105"));
        assertThat(drained.prints()).extracting(Print::at).isSorted();
    }

    private static Print print(String price, Instant at, boolean takerSold) {
        return new Print(new BigDecimal(price), BigDecimal.ONE, takerSold, at);
    }
}
