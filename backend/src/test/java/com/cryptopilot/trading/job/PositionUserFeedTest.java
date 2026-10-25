package com.cryptopilot.trading.job;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.trading.dto.response.PositionUpdateResponse;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.model.enums.CloseReason;
import com.cryptopilot.trading.model.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

/** The push of a closed position to its owner's queue, and a push that fails. */
class PositionUserFeedTest {

    private final SimpMessageSendingOperations broker = mock(SimpMessageSendingOperations.class);
    private final PositionUserFeed feed = new PositionUserFeed(broker);

    private final PositionClosed closed = new PositionClosed(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            MarketType.FUTURES,
            UUID.randomUUID(),
            "BTCUSDT",
            CloseReason.LIQUIDATION,
            new BigDecimal("90.37"),
            new BigDecimal("-19.26"),
            new BigDecimal("0.76296"),
            new BigDecimal("-20.02296"),
            false,
            Instant.parse("2026-10-25T09:00:00Z"));

    @Test
    void UC20_aClosedPosition_isPushedToItsOwnersPositionQueue() {
        feed.onClosed(closed);

        verify(broker)
                .convertAndSendToUser(
                        closed.userId().toString(),
                        "/queue/positions",
                        new PositionUpdateResponse(
                                closed.positionId(),
                                closed.planId(),
                                MarketType.FUTURES,
                                "BTCUSDT",
                                PositionStatus.CLOSED,
                                CloseReason.LIQUIDATION,
                                new BigDecimal("90.37"),
                                closed.closedAt(),
                                new BigDecimal("-19.26"),
                                new BigDecimal("0.76296"),
                                new BigDecimal("-20.02296"),
                                false));
    }

    @Test
    void UC20_aPushThatFails_isDropped() {
        doThrow(new MessageDeliveryException("broker down"))
                .when(broker)
                .convertAndSendToUser(anyString(), eq("/queue/positions"), any(Object.class));

        assertThatNoException().isThrownBy(() -> feed.onClosed(closed));
    }
}
