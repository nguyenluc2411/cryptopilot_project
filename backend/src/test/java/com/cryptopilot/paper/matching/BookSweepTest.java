package com.cryptopilot.paper.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.market.OrderBookDepth.PriceLevel;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.paper.matching.BookSweep.Result;
import com.cryptopilot.paper.matching.BookSweep.Slice;
import com.cryptopilot.paper.model.enums.OrderSide;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An order taking the levels of the book on arrival, as Binance matches it: level by level at each level's price, a
 * LIMIT order never past its price, and what the depth shown cannot hold at its last price, flagged.
 */
class BookSweepTest {

    private static final List<PriceLevel> ASKS = List.of(level("100", "1"), level("101", "2"), level("102", "3"));
    private static final List<PriceLevel> BIDS = List.of(level("99", "1"), level("98", "2"));
    private static final PairFilters FILTERS =
            new PairFilters(new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));

    @Test
    void QT7_aMarketBuy_walksTheAsksBestFirst_untilItHasItsQuantity() {
        Result sweep = BookSweep.ofQuantity(ASKS, OrderSide.BUY, new BigDecimal("2.5"), null);

        assertThat(slices(sweep)).containsExactly(slice("100", "1"), slice("101", "1.5"));
        assertThat(sweep.pastBook()).isFalse();
        assertThat(sweep.quantity()).isEqualByComparingTo("2.5");
        assertThat(sweep.notional()).as("100 + 151.5").isEqualByComparingTo("251.5");
    }

    @Test
    void QT7_aMarketSell_walksTheBids_andTheRestPastTheBookExecutesAtTheLastLevel_flagged() {
        Result sweep = BookSweep.ofQuantity(BIDS, OrderSide.SELL, new BigDecimal("5"), null);

        assertThat(slices(sweep)).containsExactly(slice("99", "1"), slice("98", "4"));
        assertThat(sweep.pastBook()).isTrue();
    }

    @Test
    void TR06_aLimitBuy_takesNoLevelAbovePrice_soTheRestWaits() {
        Result sweep = BookSweep.ofQuantity(ASKS, OrderSide.BUY, new BigDecimal("5"), new BigDecimal("101"));

        assertThat(slices(sweep)).containsExactly(slice("100", "1"), slice("101", "2"));
        assertThat(sweep.pastBook()).as("a level above its price is shown").isFalse();
        assertThat(BookSweep.ofQuantity(ASKS, OrderSide.BUY, BigDecimal.ONE, new BigDecimal("99.99"))
                        .isEmpty())
                .as("below the best ask it takes nothing")
                .isTrue();
    }

    @Test
    void TR06_aLimitSellPricedBeyondEveryLevelShown_takesTheRestAtTheLastOne() {
        Result sweep = BookSweep.ofQuantity(BIDS, OrderSide.SELL, new BigDecimal("4"), new BigDecimal("90"));

        assertThat(slices(sweep)).containsExactly(slice("99", "1"), slice("98", "3"));
        assertThat(sweep.pastBook()).isTrue();
    }

    @Test
    void QT7_aLevelOfUnknownDepth_executesTheWholeOrderAtItsPrice() {
        List<PriceLevel> last = List.of(level("100", "0"));

        assertThat(slices(BookSweep.ofQuantity(last, OrderSide.BUY, new BigDecimal("3"), null)))
                .containsExactly(slice("100", "3"));
        assertThat(slices(BookSweep.ofQuote(last, new BigDecimal("250"), FILTERS)))
                .containsExactly(slice("100", "2.5"));
    }

    @Test
    void QT7_aBuyByTotal_spendsLevelByLevel_onTheStep_leavingWhatBuysNoStepUnspent() {
        Result sweep = BookSweep.ofQuote(ASKS, new BigDecimal("200"), FILTERS);

        assertThat(slices(sweep))
                .as("100 at 100, then 100 / 101 = 0.990099 floored to 0.990")
                .containsExactly(slice("100", "1"), slice("101", "0.99"));
        assertThat(sweep.pastBook()).isFalse();
        assertThat(sweep.notional()).isEqualByComparingTo("199.99");
    }

    @Test
    void QT7_aBuyByTotalLargerThanTheBook_spendsTheRestAtTheLastLevel_flagged() {
        Result sweep = BookSweep.ofQuote(ASKS, new BigDecimal("1000"), FILTERS);

        // 100 + 202 + 306 = 608 spent on the book, 392 / 102 = 3.843137 floored to 3.843.
        assertThat(slices(sweep)).containsExactly(slice("100", "1"), slice("101", "2"), slice("102", "6.843"));
        assertThat(sweep.pastBook()).isTrue();
    }

    @Test
    void QT7_aBuyByTotalTooSmallForOneStep_takesNothing() {
        assertThat(BookSweep.ofQuote(ASKS, new BigDecimal("0.05"), FILTERS).isEmpty())
                .isTrue();
    }

    @Test
    void QT7_aSweepNeedsALevel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BookSweep.ofQuantity(List.of(), OrderSide.BUY, BigDecimal.ONE, null));
        assertThatIllegalArgumentException().isThrownBy(() -> BookSweep.ofQuote(List.of(), BigDecimal.ONE, FILTERS));
    }

    private static PriceLevel level(String price, String quantity) {
        return new PriceLevel(new BigDecimal(price), new BigDecimal(quantity));
    }

    private static String slice(String price, String quantity) {
        return price + " x " + quantity;
    }

    private static List<String> slices(Result sweep) {
        return sweep.slices().stream().map(BookSweepTest::text).toList();
    }

    private static String text(Slice slice) {
        return slice.price().stripTrailingZeros().toPlainString() + " x "
                + slice.quantity().stripTrailingZeros().toPlainString();
    }
}
