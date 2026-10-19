package com.cryptopilot.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.model.FeeSchedule;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The fee rates of each market come from the system settings; only Futures has a clearance fee.
 *
 * <p>Rule: BR-36; D-97.
 */
class FeeRatesTest {

    private final SystemSettingApi settings = mock(SystemSettingApi.class);
    private final FeeRates rates = new FeeRates(settings);

    @Test
    void BR36_futures_readsMakerTakerAndClearance() {
        when(settings.decimal(FeeRates.FUTURES_MAKER)).thenReturn(new BigDecimal("0.0002"));
        when(settings.decimal(FeeRates.FUTURES_TAKER)).thenReturn(new BigDecimal("0.0005"));
        when(settings.decimal(FeeRates.LIQUIDATION_CLEARANCE)).thenReturn(new BigDecimal("0.0125"));

        FeeSchedule fees = rates.of(MarketType.FUTURES);

        assertThat(fees.maker()).isEqualByComparingTo("0.0002");
        assertThat(fees.taker()).isEqualByComparingTo("0.0005");
        assertThat(fees.liquidationClearance()).isEqualByComparingTo("0.0125");
    }

    @Test
    void BR36_spot_hasNoClearanceFee() {
        when(settings.decimal(FeeRates.SPOT_MAKER)).thenReturn(new BigDecimal("0.001"));
        when(settings.decimal(FeeRates.SPOT_TAKER)).thenReturn(new BigDecimal("0.001"));

        FeeSchedule fees = rates.of(MarketType.SPOT);

        assertThat(fees.maker()).isEqualByComparingTo("0.001");
        assertThat(fees.taker()).isEqualByComparingTo("0.001");
        assertThat(fees.liquidationClearance()).isNull();
        verify(settings, never()).decimal(FeeRates.LIQUIDATION_CLEARANCE);
    }
}
