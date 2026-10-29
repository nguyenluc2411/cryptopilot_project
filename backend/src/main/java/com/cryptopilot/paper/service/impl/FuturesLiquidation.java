package com.cryptopilot.paper.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.market.TradablePair;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.model.LiquidationLevel;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The liquidation of isolated Futures positions (TR-22, plan 4.12): the levels the matching engine watches, and the
 * liquidation itself once a mark reached one, decided again on the database under the Trader's lock.
 *
 * <ol>
 *   <li>A position is liquidated when the mark reaches its liquidation price as shown, or its margin balance at the
 *       mark, {@code IW + uPnL}, is at most its maintenance margin; a stale level that no longer holds liquidates
 *       nothing.
 *   <li>Every open order of the pair, conditional ones included, is cancelled ({@code LIQUIDATION}).
 *   <li>A system MARKET order, {@code autoclose-…} as the exchange names it, closes all of the position at that mark,
 *       not at the book: the exchange's liquidation price is the mark that reached it.
 *   <li>The clearance fee and the loss follow the isolated cash-flow rule ({@link FuturesSettlement#liquidate}).
 * </ol>
 *
 * <p>Cross liquidation, which spans the pairs of an account, is {@link FuturesCrossLiquidation} (TR-23).
 *
 * <p>Rule: TR-22; D-97, D-127.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesLiquidation {

    /** The system setting of the clearance fee rate (D-97). */
    static final String CLEARANCE_FEE = "LIQUIDATION_CLEARANCE_FEE";

    /** The client order id prefix of a liquidation order, as the exchange's. */
    static final String AUTOCLOSE = "autoclose-";

    /** Why an order was cancelled: its position was liquidated. */
    static final String LIQUIDATION = "LIQUIDATION";

    /** What a liquidation cancels: the working orders and the take profits and stop losses still pending. */
    static final Set<OrderStatus> CANCELLED_ON_LIQUIDATION =
            EnumSet.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_NEW);

    /** The tick of a price column, for a pair whose filters are no longer read. */
    private static final BigDecimal FINEST_TICK = BigDecimal.ONE.movePointLeft(FuturesMarginMath.PRICE_SCALE);

    private static final Logger log = LoggerFactory.getLogger(FuturesLiquidation.class);

    private final FuturesPositionRepository positions;
    private final PaperOrderRepository orders;
    private final FuturesSettlement settlement;
    private final PaperUserFeed feed;
    private final MarketApi market;
    private final SystemSettingApi settings;
    private final ApplicationEventPublisher events;

    /** The levels of every open isolated position, which the engine starts watching from. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<LiquidationLevel> levels() {
        return positions.findByPositionStatusAndMarginType(PositionStatus.OPEN, MarginType.ISOLATED).stream()
                .map(this::levelOf)
                .toList();
    }

    /** The levels of the Trader's open isolated positions on the pair, as they are now. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<LiquidationLevel> levels(UUID userId, UUID pairId) {
        return positions.findByUserIdAndPairIdAndPositionStatus(userId, pairId, PositionStatus.OPEN).stream()
                .filter(position -> position.getMarginType() == MarginType.ISOLATED)
                .map(this::levelOf)
                .toList();
    }

    /**
     * Liquidates the position if the mark still reaches it; see the class comment.
     *
     * @return whether it was liquidated
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean liquidate(PaperAccount account, UUID positionId, BigDecimal mark, Instant at) {
        Optional<FuturesPosition> found = positions.findById(positionId);
        if (found.isEmpty() || !found.get().isOpen() || found.get().getMarginType() != MarginType.ISOLATED) {
            return false;
        }
        FuturesPosition position = found.get();
        List<LeverageTier> tiers = market.leverageBrackets(position.getPairId());
        BigDecimal notional = position.getQuantity().multiply(mark);
        BigDecimal marginBalance = position.getIsolatedWalletAmount()
                .add(FuturesMarginMath.unrealizedPnl(
                        position.getDirection(), position.getQuantity(), position.getEntryPrice(), mark));
        // The liquidation price shown is rounded towards the position, so the mark at it may leave a sliver above the
        // maintenance margin: reaching the price shown liquidates, as on the exchange.
        if (marginBalance.compareTo(maintenance(tiers, notional)) > 0
                && !levelOf(position).reachedBy(mark)) {
            return false;
        }
        cancelOrders(account, position.getPairId(), at);
        OrderSide side = position.getDirection() == PositionDirection.LONG ? OrderSide.SELL : OrderSide.BUY;
        PaperOrder order = orders.save(PaperOrder.futuresMarket(
                account.userId(),
                position.getPairId(),
                AUTOCLOSE + at.toEpochMilli() + "-"
                        + position.getId().toString().substring(0, 8),
                side,
                position.getPositionSide(),
                position.getQuantity(),
                true));
        settlement.liquidate(account, order, position, mark, settings.decimal(CLEARANCE_FEE), at);
        log.info(
                "TR-22 isolated position {} of user {} liquidated at mark {}",
                position.getId(),
                account.userId(),
                mark);
        return true;
    }

    /** Cancels every open order of the Trader on the pair, conditional ones included. */
    private void cancelOrders(PaperAccount account, UUID pairId, Instant at) {
        for (PaperOrder order : orders.findByUserIdAndPairIdAndMarketTypeAndOrderStatusInOrderByCreatedAtAscIdAsc(
                account.userId(), pairId, MarketType.FUTURES, CANCELLED_ON_LIQUIDATION)) {
            order.cancel(LIQUIDATION, at);
            feed.futuresOrder(account, order);
            events.publishEvent(new PaperOrderCanceled(order.getId()));
        }
    }

    /** The level of an isolated position: its liquidation price from its own wallet (plan 4.5). */
    private LiquidationLevel levelOf(FuturesPosition position) {
        BigDecimal tick = market.tradablePair(position.getPairId(), MarketType.FUTURES)
                .map(TradablePair::filters)
                .map(PairFilters::tickSize)
                .orElse(FINEST_TICK);
        BigDecimal price = FuturesMarginMath.liquidationPrice(
                position.getDirection(),
                position.getQuantity(),
                position.getEntryPrice(),
                position.getIsolatedWalletAmount(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                market.leverageBrackets(position.getPairId()),
                tick);
        return new LiquidationLevel(
                position.getId(), position.getUserId(), position.getPairId(), position.getDirection(), price);
    }

    /** The maintenance margin by the notional's bracket, the largest bracket's above every cap. */
    static BigDecimal maintenance(List<LeverageTier> tiers, BigDecimal notional) {
        return FuturesMarginMath.liquidationMaintenance(tiers, notional);
    }
}
