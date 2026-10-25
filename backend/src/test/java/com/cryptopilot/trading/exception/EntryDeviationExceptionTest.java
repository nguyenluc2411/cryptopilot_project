package com.cryptopilot.trading.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.trading.model.EntryDeviation;
import com.cryptopilot.trading.model.EntryDeviation.Verdict;
import com.cryptopilot.trading.model.PlanCosts;
import com.cryptopilot.trading.model.PlanSnapshot;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * MSG51 and MSG52 carry their values as decimal texts, in the order of the message's placeholders and by name, and
 * each is raised only for its own verdict.
 *
 * <p>Rule: BR-69; MSG51, MSG52.
 */
class EntryDeviationExceptionTest {

    private static final BigDecimal FILL = new BigDecimal("101000");

    private static final PlanSnapshot RECALCULATED = new PlanSnapshot(
            new BigDecimal("0.033300000000"),
            new BigDecimal("3363.30000000"),
            null,
            null,
            new BigDecimal("99.90000000"),
            new BigDecimal("166.50000000"),
            new BigDecimal("1.66666667"),
            null,
            new PlanCosts(
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    new BigDecimal("1.49828071")));

    @Test
    void MSG51_carriesTheDeviationQuantityAndRiskReward() {
        EntryDeviationException e = EntryDeviationException.confirmationRequired(
                deviation("0.50000000", false, Verdict.CONFIRM), FILL, RECALCULATED);

        assertThat(e.errorCode()).isEqualTo(ErrorCode.ENTRY_DEVIATION_CONFIRMATION_REQUIRED);
        assertThat(e.messageArgs()).containsExactly("0.50000000", "0.033300000000", "1.66666667");
        assertThat(e.problemProperties())
                .containsEntry(EntryDeviationException.NET_RISK_REWARD, "1.49828071")
                .doesNotContainKey(EntryDeviationException.TAKE_PROFIT_REACHED);
        assertThat(e.getMessage()).contains("0.50000000R");
    }

    @Test
    void MSG52_carriesTheDeviationAndTheLimit_andSaysWhichOneRefusedIt() {
        EntryDeviationException beyond =
                EntryDeviationException.refused(deviation("1.00000000", false, Verdict.REFUSE), FILL);
        EntryDeviationException pastTheTakeProfit =
                EntryDeviationException.refused(deviation("3.00000000", true, Verdict.REFUSE), FILL);

        assertThat(beyond.errorCode()).isEqualTo(ErrorCode.ENTRY_DEVIATION_REFUSED);
        assertThat(beyond.messageArgs()).containsExactly("1.00000000", "0.75");
        assertThat(beyond.problemProperties()).containsEntry(EntryDeviationException.TAKE_PROFIT_REACHED, false);
        assertThat(beyond.getMessage()).contains("beyond the limit of 0.75R");
        assertThat(pastTheTakeProfit.problemProperties())
                .containsEntry(EntryDeviationException.TAKE_PROFIT_REACHED, true);
        assertThat(pastTheTakeProfit.getMessage()).contains("take profit");
    }

    @Test
    void eachMessage_isRaisedOnlyForItsOwnVerdict() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntryDeviationException.confirmationRequired(
                        deviation("1", false, Verdict.REFUSE), FILL, RECALCULATED));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntryDeviationException.refused(deviation("0.5", false, Verdict.CONFIRM), FILL));
    }

    private static EntryDeviation deviation(String r, boolean takeProfitReached, Verdict verdict) {
        return new EntryDeviation(new BigDecimal(r), new BigDecimal("0.75"), takeProfitReached, verdict);
    }
}
