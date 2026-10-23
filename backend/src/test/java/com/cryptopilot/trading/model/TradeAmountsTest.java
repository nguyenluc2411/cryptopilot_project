package com.cryptopilot.trading.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The one net profit and loss formula: realized − fees + funding, Spot funding null.
 *
 * <p>Rule: BR-38; D-97 rule 4; TECHNICAL_DESIGN 7.8.
 */
class TradeAmountsTest {

    @ParameterizedTest
    @CsvSource({
        "30, 0.086, -0.0202, 29.8938", // TECHNICAL_DESIGN 7.8 vector
        "-10, 0.205, 0.0202, -10.1848", // T-044 (b) with funding received
        "-19.26, 0.76296, -0.0202, -20.04316", // T-044 (c) with funding paid
        "20, 0.15, 0, 19.85" // Futures with no settlement while open
    })
    void BR38_netIsRealizedMinusFeesPlusFunding(String realized, String fee, String funding, String net) {
        TradeAmounts amounts = new TradeAmounts(new BigDecimal(realized), new BigDecimal(fee), new BigDecimal(funding));

        assertThat(amounts.netPnl()).isEqualByComparingTo(net);
    }

    /** T-044 (d), Spot: funding null adds nothing. */
    @Test
    void BR38_spotFundingIsNull_andNetIsRealizedMinusFees() {
        assertThat(TradeAmounts.netPnl(new BigDecimal("1000"), new BigDecimal("61"), null))
                .isEqualByComparingTo("939");
    }

    /** {@code TradeResult#netPnl} is the same formula, not a second one (review P2-1 of T-044). */
    @Test
    void BR38_aTradeResult_delegatesToTheSameFormula() {
        TradeResult result = new TradeResult(
                new BigDecimal("30"), new BigDecimal("0.04"), new BigDecimal("0.046"), new BigDecimal("-0.0202"));

        assertThat(result.amounts())
                .isEqualTo(new TradeAmounts(new BigDecimal("30"), new BigDecimal("0.086"), new BigDecimal("-0.0202")));
        assertThat(result.netPnl()).isEqualByComparingTo(result.amounts().netPnl());
        assertThat(result.netPnl()).isEqualByComparingTo("29.8938");
    }
}
