package com.cryptopilot.trading.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.LiquidityRole;
import org.junit.jupiter.api.Test;

/**
 * How an exit pays, as the plan's panel and its result estimate it. The exits themselves are paper orders (RF-33).
 *
 * <p>Rule: BR-36; D-111 E1.
 */
class ExitRuleTest {

    /** D-111 E1: a take profit rested, so it is maker; a stop loss and a manual close took liquidity. */
    @Test
    void BR36_theLiquidityRoleOfAnExit_followsWhetherItRested() {
        assertThat(ExitRule.liquidity(CloseReason.TAKE_PROFIT)).isEqualTo(LiquidityRole.MAKER);
        assertThat(ExitRule.liquidity(CloseReason.STOP_LOSS)).isEqualTo(LiquidityRole.TAKER);
        assertThat(ExitRule.liquidity(CloseReason.MANUAL)).isEqualTo(LiquidityRole.TAKER);
        assertThatIllegalArgumentException().isThrownBy(() -> ExitRule.liquidity(CloseReason.LIQUIDATION));
    }
}
