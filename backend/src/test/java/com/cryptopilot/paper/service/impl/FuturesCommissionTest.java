package com.cryptopilot.paper.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.service.FuturesCommissionPolicy;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The commission of paper Futures (TR-44, plan 2.3): zero for now, and a fill refuses any other rate rather than take
 * it without the FEE ledger row the fee work brings.
 */
class FuturesCommissionTest {

    private static final PaperOrder ORDER = PaperOrder.futuresMarket(
            UUID.randomUUID(), UUID.randomUUID(), "k", OrderSide.BUY, PositionSide.BOTH, BigDecimal.ONE, false);

    @Test
    void TR44_theCommission_isZeroForMakerAndTaker() {
        FuturesCommissionPolicy zero = new ZeroFuturesCommissionPolicy();

        assertThat(zero.rate(UUID.randomUUID(), UUID.randomUUID(), Liquidity.MAKER))
                .isZero();
        assertThat(FuturesSettlement.commissionOf(zero, ORDER, Liquidity.TAKER)).isZero();
    }

    @Test
    void TR44_aCommissionOtherThanZero_isRefusedUntilFeesAreSettled() {
        FuturesCommissionPolicy configured = (userId, pairId, liquidity) -> new BigDecimal("0.0002");

        assertThatIllegalStateException()
                .isThrownBy(() -> FuturesSettlement.commissionOf(configured, ORDER, Liquidity.MAKER))
                .withMessageContaining("0.0002");
    }
}
