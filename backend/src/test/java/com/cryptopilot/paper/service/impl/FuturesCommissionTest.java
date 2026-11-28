package com.cryptopilot.paper.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.service.FuturesCommissionPolicy;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The commission rate of paper Futures (TR-44, plan 2.3): the system settings the trading plan estimates with, maker
 * for a waiting order filled, taker otherwise; a negative rate is refused.
 */
class FuturesCommissionTest {

    private static final PaperOrder ORDER = PaperOrder.futuresMarket(
            UUID.randomUUID(), UUID.randomUUID(), "k", OrderSide.BUY, PositionSide.BOTH, BigDecimal.ONE, false);

    @Test
    void TR44_theRates_areTheSettingsOfThePlan() {
        SystemSettingApi settings = mock(SystemSettingApi.class);
        when(settings.decimal("FUTURES_MAKER_FEE")).thenReturn(new BigDecimal("0.0002"));
        when(settings.decimal("FUTURES_TAKER_FEE")).thenReturn(new BigDecimal("0.0005"));
        FuturesCommissionPolicy policy = new SettingFuturesCommissionPolicy(settings);

        assertThat(policy.rate(UUID.randomUUID(), UUID.randomUUID(), Liquidity.MAKER))
                .isEqualByComparingTo("0.0002");
        assertThat(FuturesSettlement.commissionOf(policy, ORDER, Liquidity.TAKER))
                .isEqualByComparingTo("0.0005");
    }

    @Test
    void TR44_aZeroRate_isTaken() {
        FuturesCommissionPolicy zero = (userId, pairId, liquidity) -> BigDecimal.ZERO;

        assertThat(FuturesSettlement.commissionOf(zero, ORDER, Liquidity.MAKER)).isZero();
    }

    @Test
    void TR44_aNegativeRate_isRefused() {
        FuturesCommissionPolicy negative = (userId, pairId, liquidity) -> new BigDecimal("-0.0001");

        assertThatIllegalStateException()
                .isThrownBy(() -> FuturesSettlement.commissionOf(negative, ORDER, Liquidity.MAKER))
                .withMessageContaining("-0.0001");
    }
}
