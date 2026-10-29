package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.CoinListing;
import com.cryptopilot.market.MarketApi;
import com.cryptopilot.market.PairListing;
import com.cryptopilot.paper.calculator.FuturesMarginMath;
import com.cryptopilot.paper.entity.FuturesMarginAdjustment;
import com.cryptopilot.paper.entity.FuturesPosition;
import com.cryptopilot.paper.entity.FuturesSymbolConfig;
import com.cryptopilot.paper.entity.PaperFill;
import com.cryptopilot.paper.entity.PaperLedgerEntry;
import com.cryptopilot.paper.entity.PaperLedgerEntry.Ref;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.FuturesAccountChanged;
import com.cryptopilot.paper.event.PaperOrderCanceled;
import com.cryptopilot.paper.model.IsolatedCashFlow;
import com.cryptopilot.paper.model.PaperAccount;
import com.cryptopilot.paper.model.PositionFill;
import com.cryptopilot.paper.model.enums.FillSource;
import com.cryptopilot.paper.model.enums.LedgerEntryType;
import com.cryptopilot.paper.model.enums.LedgerRefType;
import com.cryptopilot.paper.model.enums.Liquidity;
import com.cryptopilot.paper.model.enums.MarginAdjustmentRefType;
import com.cryptopilot.paper.model.enums.MarginAdjustmentType;
import com.cryptopilot.paper.model.enums.MarginType;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionCloseReason;
import com.cryptopilot.paper.model.enums.PositionDirection;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesMarginAdjustmentRepository;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperFillRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one execution of a Futures order to the Trader's one-way position, in the caller's transaction, which holds
 * the Trader's lock (plan 8.3):
 *
 * <ol>
 *   <li>A reduce-only order executes at most what closes the position; with nothing to close it expires.
 *   <li>The part that closes the position is one fill on it, with the profit or loss it realises written to the
 *       Futures wallet; an isolated position releases its wallet by the cash-flow rule (A), its loss never reaching
 *       the cross wallet, and a loss beyond the wallet is an insurance cover (4.3).
 *   <li>The part that opens or increases a position is a fill on that position, a reversal being stored as two fills.
 *       An isolated position takes its margin at the fill price from the cross wallet's free cash, checked now as at
 *       placement; without enough the opening part is not executed and the order expires
 *       ({@code MARGIN_INSUFFICIENT}). A cross position is not checked at a fill, as on the exchange.
 *   <li>Every change of an isolated wallet is one {@code futures_margin_adjustment} row (10.3).
 *   <li>Once the position is smaller, the reduce-only orders of the pair that no longer fit it expire, newest first
 *       (5.2). The exchange shrinks such an order instead; an order's quantity is fixed here, so it expires. Once it is
 *       closed, its closing conditional orders (TP/SL with {@code closePosition} or reduce-only) expire too.
 *   <li>The cross wallet left below zero with no cross position open is made good by an insurance cover (4.3 rule 5).
 * </ol>
 *
 * <p>Fees are zero for now (plan 2.3): a fill records a commission of zero in USDT.
 *
 * <p>Rule: TR-16; D-127.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FuturesSettlement {

    /** Why an order expired: the isolated position it would open could not be funded at the fill. */
    static final String MARGIN_INSUFFICIENT = "MARGIN_INSUFFICIENT";

    /** Why a closing conditional order expired: the position it would close was closed. */
    static final String POSITION_CLOSED = "POSITION_CLOSED";

    /** Why a reduce-only order expired: there is no position left for it to reduce. */
    static final String REDUCE_ONLY = "REDUCE_ONLY_NOTHING_TO_REDUCE";

    private final FuturesPositionRepository positions;
    private final FuturesMarginAdjustmentRepository adjustments;
    private final PaperFillRepository fills;
    private final PaperWallets wallets;
    private final FuturesMargins margins;
    private final MarketApi market;
    private final ApplicationEventPublisher events;
    private final PaperUserFeed feed;
    private final FuturesOrderLists tpSl;
    private final EntityManager entityManager;

    /**
     * Executes up to {@code quantity} of the order at {@code price}.
     *
     * @return the quantity executed, less than asked when a reduce-only order had less to close or an isolated
     *     position could not be funded; the order is then ended
     */
    @Transactional(propagation = Propagation.MANDATORY)
    BigDecimal execute(
            PaperAccount account,
            PaperOrder order,
            BigDecimal price,
            BigDecimal quantity,
            Liquidity liquidity,
            FillSource source,
            Instant at) {
        UUID userId = account.userId();
        FuturesPosition position = margins.openPosition(userId, order.getPairId());
        BigDecimal size = position == null ? BigDecimal.ZERO : position.signedQuantity();
        BigDecimal wanted = quantity;
        if (order.isReduceOnly()) {
            wanted = wanted.min(closableBy(order.getSide(), size));
            if (wanted.signum() == 0) {
                end(account, order, REDUCE_ONLY, at);
                return BigDecimal.ZERO;
            }
        }
        PositionFill effect = FuturesMarginMath.applyFill(
                size, position == null ? null : position.getEntryPrice(), order.getSide(), wanted, price);
        CoinListing usdt = margins.usdt();
        BigDecimal closed = effect.closedQuantity();
        if (closed.signum() > 0) {
            close(account, order, position, effect, price, liquidity, source, at, usdt);
            if (effect.reverses()) {
                // The closed row must reach the database before the new one, as only one position may be open.
                entityManager.flush();
            }
        }
        BigDecimal opened = effect.openedQuantity();
        boolean funded = true;
        if (opened.signum() > 0) {
            funded = open(
                    account, order, effect.reverses() ? null : position, opened, price, liquidity, source, at, usdt);
        }
        BigDecimal executed = funded ? closed.add(opened) : closed;
        if (executed.signum() > 0) {
            order.fill(executed, price, Rounding.toAmount(price.multiply(executed)), at);
        }
        if (!funded && order.isOpen()) {
            end(account, order, MARGIN_INSUFFICIENT, at);
        }
        feed.futuresOrder(account, order);
        if (closed.signum() > 0) {
            trimReduceOnly(account, order.getPairId(), at);
            coverCrossDeficit(account, userId, position, usdt);
        }
        margins.recordChange(margins.accountConfig(userId));
        events.publishEvent(new FuturesAccountChanged(userId, order.getPairId()));
        return executed;
    }

    /**
     * Liquidates an isolated position (TR-22, plan 4.12): the system's MARKET order closes all of it at the mark that
     * reached it, the clearance fee is {@code min(notional × rate, what the wallet has left)}, and the realised loss
     * and the fee are one closing event of the isolated cash-flow rule (A): the cross wallet never pays for it, and a
     * loss beyond the wallet is an insurance cover. The orders of the pair were cancelled by the caller.
     *
     * @param order the stored system MARKET order, reduce-only, of the position's size
     * @param feeRate the liquidation clearance fee rate (D-97)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void liquidate(
            PaperAccount account,
            PaperOrder order,
            FuturesPosition position,
            BigDecimal mark,
            BigDecimal feeRate,
            Instant at) {
        CoinListing usdt = margins.usdt();
        BigDecimal quantity = position.getQuantity();
        BigDecimal realized = FuturesMarginMath.applyFill(
                        position.signedQuantity(), position.getEntryPrice(), order.getSide(), quantity, mark)
                .realizedPnl();
        BigDecimal wallet = position.getIsolatedWalletAmount();
        BigDecimal fee = FuturesMarginMath.liquidationFee(
                quantity.multiply(mark), feeRate, wallet.add(realized).max(BigDecimal.ZERO));
        PaperFill fill = fills.save(PaperFill.futures(
                order,
                execution(mark, quantity, Liquidity.TAKER, FillSource.LIVE, at, usdt),
                position.getId(),
                realized));
        feed.futuresFill(account, order, fill);
        Ref byFill = Ref.to(LedgerRefType.FILL, fill.getId());
        if (realized.signum() != 0) {
            wallets.changeFutures(account, position.getPairId(), usdt, realized, LedgerEntryType.REALIZED_PNL, byFill);
        }
        if (fee.signum() > 0) {
            wallets.changeFutures(
                    account, position.getPairId(), usdt, fee.negate(), LedgerEntryType.LIQUIDATION_FEE, byFill);
        }
        IsolatedCashFlow flow = FuturesMarginMath.closeFlow(wallet, quantity, quantity, realized, fee);
        PaperLedgerEntry cover = null;
        if (flow.insuranceCover().signum() > 0) {
            cover = wallets.changeFutures(
                    account,
                    position.getPairId(),
                    usdt,
                    flow.insuranceCover(),
                    LedgerEntryType.INSURANCE_COVER,
                    Ref.to(LedgerRefType.POSITION, position.getId()));
        }
        if (flow.isolatedWalletChange().signum() != 0) {
            position.adjustIsolatedWallet(flow.isolatedWalletChange());
            adjustments.save(
                    cover == null
                            ? FuturesMarginAdjustment.of(
                                    position,
                                    MarginAdjustmentType.LIQUIDATION,
                                    flow.isolatedWalletChange(),
                                    MarginAdjustmentRefType.FILL,
                                    fill.getId())
                            : FuturesMarginAdjustment.of(
                                    position,
                                    MarginAdjustmentType.LIQUIDATION,
                                    flow.isolatedWalletChange(),
                                    MarginAdjustmentRefType.LEDGER,
                                    cover.getId()));
        }
        position.reduce(quantity, mark, realized, PositionCloseReason.LIQUIDATION, at);
        position.addLiquidationFee(fee);
        order.fill(quantity, mark, Rounding.toAmount(mark.multiply(quantity)), at);
        feed.futuresOrder(account, order);
        trimReduceOnly(account, order.getPairId(), at);
        margins.recordChange(margins.accountConfig(account.userId()));
        events.publishEvent(new FuturesAccountChanged(account.userId(), order.getPairId()));
    }

    /**
     * Liquidates a cross position (TR-23, plan 4.12): the system's MARKET order closes all of it at the mark, the
     * realised loss goes to the cross wallet, which may go below zero meanwhile (4.3 rule 5), and the clearance fee is
     * {@code min(notional × rate, what the cross margin balance has left after the loss)}. With no cross position left,
     * a cross wallet below zero is made good by an insurance cover. The orders were cancelled by the caller.
     *
     * @param order the stored system MARKET order, reduce-only, of the position's size
     * @param feeRate the liquidation clearance fee rate (D-97)
     * @param otherUnrealizedPnl the unrealized profit of the Trader's other cross positions at their marks
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void liquidateCross(
            PaperAccount account,
            PaperOrder order,
            FuturesPosition position,
            BigDecimal mark,
            BigDecimal feeRate,
            BigDecimal otherUnrealizedPnl,
            Instant at) {
        CoinListing usdt = margins.usdt();
        UUID userId = account.userId();
        BigDecimal quantity = position.getQuantity();
        BigDecimal realized = FuturesMarginMath.applyFill(
                        position.signedQuantity(), position.getEntryPrice(), order.getSide(), quantity, mark)
                .realizedPnl();
        BigDecimal isolated = positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN).stream()
                .filter(open -> open.getMarginType() == MarginType.ISOLATED)
                .map(FuturesPosition::getIsolatedWalletAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal crossWallet = margins.futuresWallet(userId).subtract(isolated);
        BigDecimal fee = FuturesMarginMath.liquidationFee(
                quantity.multiply(mark),
                feeRate,
                crossWallet.add(realized).add(otherUnrealizedPnl).max(BigDecimal.ZERO));
        PaperFill fill = fills.save(PaperFill.futures(
                order,
                execution(mark, quantity, Liquidity.TAKER, FillSource.LIVE, at, usdt),
                position.getId(),
                realized));
        feed.futuresFill(account, order, fill);
        Ref byFill = Ref.to(LedgerRefType.FILL, fill.getId());
        if (realized.signum() != 0) {
            wallets.changeFutures(account, position.getPairId(), usdt, realized, LedgerEntryType.REALIZED_PNL, byFill);
        }
        if (fee.signum() > 0) {
            wallets.changeFutures(
                    account, position.getPairId(), usdt, fee.negate(), LedgerEntryType.LIQUIDATION_FEE, byFill);
        }
        position.reduce(quantity, mark, realized, PositionCloseReason.LIQUIDATION, at);
        position.addLiquidationFee(fee);
        order.fill(quantity, mark, Rounding.toAmount(mark.multiply(quantity)), at);
        feed.futuresOrder(account, order);
        entityManager.flush();
        coverCrossDeficit(account, userId, position, usdt);
        margins.recordChange(margins.accountConfig(userId));
        events.publishEvent(new FuturesAccountChanged(userId, order.getPairId()));
    }

    /** The part of a fill that reduces or closes the position, and the isolated cash flow it makes. */
    private void close(
            PaperAccount account,
            PaperOrder order,
            FuturesPosition position,
            PositionFill effect,
            BigDecimal price,
            Liquidity liquidity,
            FillSource source,
            Instant at,
            CoinListing usdt) {
        BigDecimal closed = effect.closedQuantity();
        BigDecimal realized = effect.realizedPnl();
        BigDecimal sizeBefore = position.getQuantity();
        PaperFill fill = fills.save(PaperFill.futures(
                order, execution(price, closed, liquidity, source, at, usdt), position.getId(), realized));
        feed.futuresFill(account, order, fill);
        if (realized.signum() != 0) {
            wallets.changeFutures(
                    account,
                    position.getPairId(),
                    usdt,
                    realized,
                    LedgerEntryType.REALIZED_PNL,
                    Ref.to(LedgerRefType.FILL, fill.getId()));
        }
        if (position.getMarginType() == MarginType.ISOLATED) {
            IsolatedCashFlow flow = FuturesMarginMath.closeFlow(
                    position.getIsolatedWalletAmount(), closed, sizeBefore, realized, BigDecimal.ZERO);
            PaperLedgerEntry cover = null;
            if (flow.insuranceCover().signum() > 0) {
                cover = wallets.changeFutures(
                        account,
                        position.getPairId(),
                        usdt,
                        flow.insuranceCover(),
                        LedgerEntryType.INSURANCE_COVER,
                        Ref.to(LedgerRefType.POSITION, position.getId()));
            }
            BigDecimal change = flow.isolatedWalletChange();
            if (change.signum() != 0) {
                position.adjustIsolatedWallet(change);
                MarginAdjustmentType type = closed.compareTo(sizeBefore) == 0
                        ? MarginAdjustmentType.CLOSE
                        : flow.branch() == IsolatedCashFlow.Branch.RELEASE
                                ? MarginAdjustmentType.RELEASE
                                : MarginAdjustmentType.LOSS_ABSORB;
                adjustments.save(
                        cover == null
                                ? FuturesMarginAdjustment.of(
                                        position, type, change, MarginAdjustmentRefType.FILL, fill.getId())
                                : FuturesMarginAdjustment.of(
                                        position, type, change, MarginAdjustmentRefType.LEDGER, cover.getId()));
            }
        }
        position.reduce(closed, price, realized, PositionCloseReason.MANUAL, at);
    }

    /**
     * The part of a fill that opens or increases a position. An isolated one is funded from the cross wallet's free
     * cash, the order's own rest no longer counted as waiting (4.3 rule 6).
     *
     * @param position the position it increases, or {@code null} to open one
     * @return whether it was executed; {@code false} when an isolated position could not be funded
     */
    private boolean open(
            PaperAccount account,
            PaperOrder order,
            FuturesPosition position,
            BigDecimal opened,
            BigDecimal price,
            Liquidity liquidity,
            FillSource source,
            Instant at,
            CoinListing usdt) {
        UUID userId = account.userId();
        PairListing pair = market.pairListings(List.of(order.getPairId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not stored"));
        FuturesSymbolConfig config =
                margins.symbolConfig(userId, pair.pairId(), market.leverageBrackets(pair.pairId()));
        PositionDirection direction = PositionDirection.openedBy(order.getSide());
        BigDecimal margin = BigDecimal.ZERO;
        if (config.getMarginType() == MarginType.ISOLATED) {
            BigDecimal mark = market.currentMarkPrice(pair.symbol()).orElse(price);
            margin = FuturesMarginMath.isolatedFillMargin(direction, opened, price, mark, config.getLeverage());
            BigDecimal free =
                    margins.crossState(userId, order.getId(), true).margin().crossFreeCash();
            if (margin.compareTo(free) > 0) {
                return false;
            }
        }
        boolean fresh = position == null;
        FuturesPosition target = fresh
                ? positions.save(FuturesPosition.open(
                        userId,
                        pair.pairId(),
                        order.getPositionSide(),
                        direction,
                        config.getMarginType(),
                        config.getLeverage(),
                        opened,
                        price,
                        at))
                : position;
        if (!fresh) {
            BigDecimal entry = FuturesMarginMath.applyFill(
                            target.signedQuantity(), target.getEntryPrice(), order.getSide(), opened, price)
                    .entryPriceAfter();
            target.increase(opened, entry);
        }
        PaperFill fill = fills.save(PaperFill.futures(
                order, execution(price, opened, liquidity, source, at, usdt), target.getId(), BigDecimal.ZERO));
        feed.futuresFill(account, order, fill);
        if (margin.signum() > 0) {
            target.adjustIsolatedWallet(margin);
            adjustments.save(FuturesMarginAdjustment.of(
                    target,
                    fresh ? MarginAdjustmentType.OPEN : MarginAdjustmentType.INCREASE,
                    margin,
                    MarginAdjustmentRefType.FILL,
                    fill.getId()));
        }
        return true;
    }

    /**
     * Ends the reduce-only orders of the pair that no longer fit the position: on each side, oldest first, an order
     * whose rest would take the side's total past what it can close expires, as does every one once the position is
     * closed (5.2).
     */
    private void trimReduceOnly(PaperAccount account, UUID pairId, Instant at) {
        UUID userId = account.userId();
        FuturesPosition position = margins.openPosition(userId, pairId);
        BigDecimal size = position == null ? BigDecimal.ZERO : position.signedQuantity();
        if (position == null) {
            // A closed position takes its TP/SL with it: the closing conditional orders of the pair end (8.3 step 8).
            for (PaperOrder conditional : margins.openConditionalOrders(userId)) {
                if (conditional.getPairId().equals(pairId)
                        && (conditional.isClosePosition() || conditional.isReduceOnly())) {
                    end(account, conditional, POSITION_CLOSED, at);
                }
            }
        }
        List<PaperOrder> reduceOnly = new ArrayList<>();
        for (PaperOrder order : margins.openOrders(userId)) {
            if (order.getPairId().equals(pairId) && order.isReduceOnly() && order.isOpen()) {
                reduceOnly.add(order);
            }
        }
        reduceOnly.sort(Comparator.comparing(PaperOrder::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PaperOrder::getId));
        BigDecimal bids = BigDecimal.ZERO;
        BigDecimal asks = BigDecimal.ZERO;
        for (PaperOrder order : reduceOnly) {
            boolean buy = order.getSide() == OrderSide.BUY;
            BigDecimal taken = (buy ? bids : asks).add(order.remainingQuantity());
            if (taken.compareTo(closableBy(order.getSide(), size)) > 0) {
                end(account, order, REDUCE_ONLY, at);
            } else if (buy) {
                bids = taken;
            } else {
                asks = taken;
            }
        }
    }

    /**
     * After a cross position closed: with no cross position left, a cross wallet below zero is made good by an
     * insurance cover, so the Trader never owes the system (4.3 rule 5). Not while another cross position is open, as
     * it may still recover the loss.
     */
    private void coverCrossDeficit(PaperAccount account, UUID userId, FuturesPosition closed, CoinListing usdt) {
        if (closed == null || closed.isOpen() || closed.getMarginType() != MarginType.CROSSED) {
            return;
        }
        BigDecimal isolated = BigDecimal.ZERO;
        for (FuturesPosition open : positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN)) {
            if (open.getMarginType() == MarginType.CROSSED) {
                return;
            }
            isolated = isolated.add(open.getIsolatedWalletAmount());
        }
        BigDecimal crossWallet = margins.futuresWallet(userId).subtract(isolated);
        if (crossWallet.signum() < 0) {
            wallets.changeFutures(
                    account,
                    closed.getPairId(),
                    usdt,
                    crossWallet.negate(),
                    LedgerEntryType.INSURANCE_COVER,
                    Ref.to(LedgerRefType.POSITION, closed.getId()));
        }
    }

    /** Ends an order the position no longer takes, and drops it from the engine's book after the commit. */
    private void end(PaperAccount account, PaperOrder order, String reason, Instant at) {
        order.expire(reason, at);
        feed.futuresOrder(account, order);
        if (order.isConditional()) {
            tpSl.settle(account, order, at);
        }
        events.publishEvent(new PaperOrderCanceled(order.getId()));
    }

    /** What an order of {@code side} can close of a one-way position of signed {@code size}: the opposite side's. */
    static BigDecimal closableBy(OrderSide side, BigDecimal size) {
        boolean closesLong = side == OrderSide.SELL;
        return closesLong
                ? size.max(BigDecimal.ZERO)
                : size.min(BigDecimal.ZERO).negate();
    }

    private static PaperFill.Execution execution(
            BigDecimal price,
            BigDecimal quantity,
            Liquidity liquidity,
            FillSource source,
            Instant at,
            CoinListing usdt) {
        return new PaperFill.Execution(
                price,
                quantity,
                Rounding.toAmount(price.multiply(quantity)),
                BigDecimal.ZERO,
                usdt.coinId(),
                liquidity,
                source,
                at);
    }
}
