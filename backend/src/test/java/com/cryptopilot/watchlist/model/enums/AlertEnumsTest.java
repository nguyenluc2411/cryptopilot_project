package com.cryptopilot.watchlist.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlertEnumsTest {

    @Test
    void A40_onlyFundingRateAndOpenInterestChange_areFuturesOnly() {
        assertThat(AlertIndicator.values())
                .filteredOn(AlertIndicator::futuresOnly)
                .containsExactly(AlertIndicator.FUNDING_RATE, AlertIndicator.OPEN_INTEREST_CHANGE);
    }

    @Test
    void TD79_macdAndEmaCrosses_areSignCrosses() {
        assertThat(AlertIndicator.values())
                .filteredOn(AlertIndicator::signCross)
                .containsExactly(AlertIndicator.MACD_CROSS, AlertIndicator.EMA_CROSS);
    }

    @Test
    void BR20_onlyTheCrossOperators_needAPreviousValue() {
        assertThat(ConditionOperator.values())
                .filteredOn(ConditionOperator::isCross)
                .containsExactly(ConditionOperator.CROSS_ABOVE, ConditionOperator.CROSS_BELOW);
    }
}
