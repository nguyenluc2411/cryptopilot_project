package com.cryptopilot.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.MarketType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The request checks shared by the market data API, for the inputs HTTP cannot send: a missing market.
 *
 * <p>Rule: UC-09; TECHNICAL_DESIGN 5.1 (MSG01).
 */
class MarketRequestsTest {

    @Test
    void UC09_aMarket_isReadInAnyCase_andAMissingOneIsMsg01() {
        assertThat(MarketRequests.market("Spot")).isEqualTo(MarketType.SPOT);
        assertThat(MarketRequests.market("futures")).isEqualTo(MarketType.FUTURES);
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> MarketRequests.market(null))
                .satisfies(refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void UC09_aRangeWithoutAStart_needsOnlyAnEndThatIsNotInTheFuture() {
        Instant now = Instant.parse("2026-09-25T10:00:00Z");

        MarketRequests.requireRange(null, now, now);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> MarketRequests.requireRange(null, now.plusMillis(1), now));
    }
}
