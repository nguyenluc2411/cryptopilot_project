package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.paper.config.PaperFuturesProperties;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.FuturesRiskNotice;
import com.cryptopilot.paper.event.PaperPositionClosed;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.service.FuturesPositionWarningService;
import com.cryptopilot.paper.service.impl.FuturesAccountServiceImpl.HeldPosition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The held-position warnings of TR-27, on the figures {@code positionRisk} shows:
 *
 * <ul>
 *   <li>NEAR_LIQUIDATION: the mark within {@code near-liquidation-percent} of the mark from the liquidation price, on
 *       the position's side of it, at most once per {@code position-warning-cooldown}. A position no price liquidates
 *       is never near.
 *   <li>NO_STOP_LOSS: a position opened {@code stop-loss-grace} ago or more with no open STOP, STOP_MARKET or
 *       TRAILING_STOP_MARKET order on the other side of its position side; once per position.
 * </ul>
 *
 * <p>When each was sent is kept in memory, as the margin calls' cooldown is, so a restart may warn once more; a
 * position's entries are dropped once it closes ({@link PaperPositionClosed}).
 *
 * <p>Rule: TR-27; D-127.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FuturesPositionWarningServiceImpl implements FuturesPositionWarningService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Set<OrderType> STOPS =
            EnumSet.of(OrderType.STOP, OrderType.STOP_MARKET, OrderType.TRAILING_STOP_MARKET);

    private final FuturesPositionRepository positions;
    private final FuturesAccountServiceImpl accounts;
    private final FuturesMargins margins;
    private final PaperFuturesProperties futures;
    private final ApplicationEventPublisher events;

    /** When each position was last warned near its liquidation price. */
    private final Map<UUID, Instant> nearWarnedAt = new ConcurrentHashMap<>();

    /** The positions already warned for having no stop loss. */
    private final Set<UUID> stopLossWarned = ConcurrentHashMap.newKeySet();

    @Override
    @Transactional(readOnly = true)
    public List<UUID> holders() {
        return positions.findByPositionStatus(PositionStatus.OPEN).stream()
                .map(FuturesPosition::getUserId)
                .distinct()
                .toList();
    }

    @Override
    @Transactional
    public int warn(UUID userId, Instant at) {
        List<HeldPosition> held = accounts.heldPositions(userId);
        List<PaperOrder> conditional = margins.openConditionalOrders(userId);
        int sent = 0;
        for (HeldPosition position : held) {
            if (nearLiquidation(position) && cooledDown(position.position().getId(), at)) {
                publish(position, FuturesRiskNotice.Kind.NEAR_LIQUIDATION, at);
                nearWarnedAt.put(position.position().getId(), at);
                sent++;
            }
            FuturesPosition opened = position.position();
            if (!opened.getOpenedAt().plus(futures.stopLossGrace()).isAfter(at)
                    && !stopLossWarned.contains(opened.getId())
                    && conditional.stream().noneMatch(order -> stopsLoss(order, opened))) {
                publish(position, FuturesRiskNotice.Kind.NO_STOP_LOSS, at);
                stopLossWarned.add(opened.getId());
                sent++;
            }
        }
        return sent;
    }

    /** A closed position is warned no more. */
    @TransactionalEventListener
    public void onClosed(PaperPositionClosed closed) {
        nearWarnedAt.remove(closed.positionId());
        stopLossWarned.remove(closed.positionId());
    }

    /** Whether the mark is within the percentage of the liquidation price, on the position's side of it. */
    private boolean nearLiquidation(HeldPosition position) {
        BigDecimal liquidation = position.liquidationPrice();
        if (liquidation.signum() <= 0) {
            return false;
        }
        BigDecimal room = position.position().getDirection() == PositionDirection.LONG
                ? position.mark().subtract(liquidation)
                : liquidation.subtract(position.mark());
        return Rounding.divide(room.multiply(HUNDRED), position.mark()).compareTo(futures.nearLiquidationPercent())
                <= 0;
    }

    private boolean cooledDown(UUID positionId, Instant at) {
        Instant last = nearWarnedAt.get(positionId);
        return last == null || !at.isBefore(last.plus(futures.positionWarningCooldown()));
    }

    /** Whether a conditional order is a stop that closes the position: a stop on the other side of its side. */
    private static boolean stopsLoss(PaperOrder order, FuturesPosition position) {
        return STOPS.contains(order.getOrderType())
                && order.getPairId().equals(position.getPairId())
                && order.getPositionSide() == position.getPositionSide()
                && PositionDirection.openedBy(order.getSide()) != position.getDirection();
    }

    private void publish(HeldPosition held, FuturesRiskNotice.Kind kind, Instant at) {
        FuturesPosition position = held.position();
        boolean near = kind == FuturesRiskNotice.Kind.NEAR_LIQUIDATION;
        events.publishEvent(new FuturesRiskNotice(
                position.getUserId(),
                kind,
                held.symbol(),
                position.getMarginType().name(),
                position.getId(),
                near ? held.mark() : null,
                null,
                at,
                near ? held.liquidationPrice() : null));
    }
}
