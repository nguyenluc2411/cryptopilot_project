package com.cryptopilot.trading.job;

import com.cryptopilot.trading.dto.response.PositionUpdateResponse;
import com.cryptopilot.trading.event.PositionClosed;
import com.cryptopilot.trading.model.enums.PositionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes the change of a plan's position to its owner's {@code /user/queue/positions} once the close has committed, so
 * the plan screen updates without a reload. The push is not part of the close: a failure is logged and dropped, and the
 * Trader sees the closed position on the next read. Orchestration only.
 *
 * <p>Rule: BR-35, UC-20; TECHNICAL_DESIGN 9; Q-57.
 */
@Component
public class PositionUserFeed {

    /** The user queue of the position changes. */
    static final String QUEUE = "/queue/positions";

    private static final Logger log = LoggerFactory.getLogger(PositionUserFeed.class);

    private final SimpMessageSendingOperations broker;

    public PositionUserFeed(SimpMessageSendingOperations broker) {
        this.broker = broker;
    }

    @TransactionalEventListener
    public void onClosed(PositionClosed closed) {
        try {
            broker.convertAndSendToUser(
                    closed.userId().toString(),
                    QUEUE,
                    new PositionUpdateResponse(
                            closed.positionId(),
                            closed.planId(),
                            closed.market(),
                            closed.symbol(),
                            PositionStatus.CLOSED,
                            closed.closeReason(),
                            closed.exitPrice(),
                            closed.closedAt(),
                            closed.realizedPnl(),
                            closed.feeAmount(),
                            closed.netPnl(),
                            closed.provisional()));
        } catch (RuntimeException failure) {
            log.warn("UC-20 position push to user {} failed: {}", closed.userId(), failure.toString());
        }
    }
}
