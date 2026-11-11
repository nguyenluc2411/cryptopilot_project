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
import com.cryptopilot.paper.event.PaperPositionClosed;
import com.cryptopilot.paper.matching.BookSweep;
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
import com.cryptopilot.paper.model.enums.PositionSide;
import com.cryptopilot.paper.model.enums.PositionStatus;
import com.cryptopilot.paper.repository.FuturesMarginAdjustmentRepository;
import com.cryptopilot.paper.repository.FuturesPositionRepository;
import com.cryptopilot.paper.repository.PaperFillRepository;
import com.cryptopilot.paper.service.FuturesCommissionPolicy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
 * <p>Fees are zero for now (plan 2.3): a fill asks {@link FuturesCommissionPolicy}, which returns zero, and records a
 * commission of zero in USDT.
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
    private final FuturesCommissionPolicy commission;

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
        return execute(account, order, price, quantity, liquidity, source, at, PositionCloseReason.MANUAL);
    }

    /**
     * As {@link #execute(PaperAccount, PaperOrder, BigDecimal, BigDecimal, Liquidity, FillSource, Instant)}, a position
     * it closes being closed for {@code closeReason}: SYSTEM when the pair was taken out of service (TR-46).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    BigDecimal execute(
            PaperAccount account,
            PaperOrder order,
            BigDecimal price,
            BigDecimal quantity,
            Liquidity liquidity,
            FillSource source,
            Instant at,
            PositionCloseReason closeReason) {
        return execute(account, order, price, quantity, liquidity, source, at, closeReason, Optional.empty(), null);
    }

    /**
     * Executes a resting order a replay reached (plan 9.5): dated when it is applied, so that the position's life follows
     * the order the database changed in, and keeping the time of the candle whose price it took.
     *
     * @param at when the fill is applied, now
     * @param candleTime the time of the candle it was decided on
     */
    @Transactional(propagation = Propagation.MANDATORY)
    BigDecimal executeReplayed(
            PaperAccount account,
            PaperOrder order,
            BigDecimal price,
            BigDecimal quantity,
            Liquidity liquidity,
            Instant at,
            Instant candleTime) {
        return execute(
                account,
                order,
                price,
                quantity,
                liquidity,
                FillSource.REPLAY,
                at,
                PositionCloseReason.MANUAL,
                Optional.empty(),
                candleTime);
    }

    /**
     * Executes a slice of a FOK order whose whole sweep was found fundable before it began ({@link #checkWhole}): the
     * slices are not funded one by one, and each takes its margin at the mark the check used, so the order can neither
     * stop part way nor move more margin than the check allowed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    BigDecimal executeWhole(
            PaperAccount account,
            PaperOrder order,
            BigDecimal price,
            BigDecimal quantity,
            Instant at,
            WholeCheck check) {
        return execute(
                account,
                order,
                price,
                quantity,
                Liquidity.TAKER,
                FillSource.LIVE,
                at,
                PositionCloseReason.MANUAL,
                Optional.of(check),
                null);
    }

    private BigDecimal execute(
            PaperAccount account,
            PaperOrder order,
            BigDecimal price,
            BigDecimal quantity,
            Liquidity liquidity,
            FillSource source,
            Instant at,
            PositionCloseReason closeReason,
            Optional<WholeCheck> whole,
            Instant candleTime) {
        UUID userId = account.userId();
        FuturesPosition position = margins.openPosition(userId, order.getPairId(), order.getPositionSide());
        BigDecimal size = position == null ? BigDecimal.ZERO : position.signedQuantity();
        BigDecimal wanted = quantity;
        if (order.reducing()) {
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
            close(account, order, position, effect, price, liquidity, source, at, candleTime, usdt, closeReason);
            if (effect.reverses()) {
                // The closed row must reach the database before the new one, as only one position may be open.
                entityManager.flush();
            }
        }
        BigDecimal opened = effect.openedQuantity();
        boolean funded = true;
        if (opened.signum() > 0) {
            funded = open(
                    account,
                    order,
                    effect.reverses() ? null : position,
                    opened,
                    price,
                    liquidity,
                    source,
                    at,
                    candleTime,
                    usdt,
                    whole);
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
            trimReduceOnly(account, order.getPairId(), order.getPositionSide(), at);
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
                execution(mark, quantity, Liquidity.TAKER, FillSource.LIVE, at, usdt, BigDecimal.ZERO),
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
        // What the cross wallet got from the liquidation: what the wallet had left after the loss and the fee.
        position.returnToCross(flow.crossChange());
        position.reduce(quantity, mark, realized, PositionCloseReason.LIQUIDATION, at);
        position.addLiquidationFee(fee);
        publishIfClosed(position);
        order.fill(quantity, mark, Rounding.toAmount(mark.multiply(quantity)), at);
        feed.futuresOrder(account, order);
        trimReduceOnly(account, order.getPairId(), order.getPositionSide(), at);
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
        BigDecimal crossWallet =
                margins.crossWallet(userId, positions.findByUserIdAndPositionStatus(userId, PositionStatus.OPEN));
        BigDecimal fee = FuturesMarginMath.liquidationFee(
                quantity.multiply(mark),
                feeRate,
                crossWallet.add(realized).add(otherUnrealizedPnl).max(BigDecimal.ZERO));
        PaperFill fill = fills.save(PaperFill.futures(
                order,
                execution(mark, quantity, Liquidity.TAKER, FillSource.LIVE, at, usdt, BigDecimal.ZERO),
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
        publishIfClosed(position);
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
            Instant candleTime,
            CoinListing usdt,
            PositionCloseReason closeReason) {
        BigDecimal closed = effect.closedQuantity();
        BigDecimal realized = effect.realizedPnl();
        BigDecimal sizeBefore = position.getQuantity();
        PaperFill fill = fills.save(PaperFill.futures(
                        order,
                        execution(
                                price, closed, liquidity, source, at, usdt, commissionOf(commission, order, liquidity)),
                        position.getId(),
                        realized)
                .takenFrom(candleTime));
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
            // What the cross wallet got from this fill: the wallet's share, with the profit or less the loss.
            position.returnToCross(flow.crossChange());
        }
        position.reduce(closed, price, realized, closeReason, at);
        publishIfClosed(position);
    }

    /** Tells whoever follows positions that this one closed, once nothing of it is left (plan 9.1 step 8). */
    private void publishIfClosed(FuturesPosition position) {
        if (!position.isOpen()) {
            events.publishEvent(new PaperPositionClosed(
                    position.getUserId(),
                    position.getId(),
                    position.getPairId(),
                    position.getCloseReason().name(),
                    position.getRealizedPnlAmount(),
                    position.getClosedAt()));
        }
    }

    /**
     * Whether a FOK order can fill whole on these slices, checked before it sweeps so that it fills all or nothing (4.3
     * rule 6). It cannot when it reduces and is larger than what the position has to close, or when it opens an
     * isolated position whose margin, all slices together at one mark, is beyond the cross wallet's free cash. The
     * slices first close what the position has on the other side, which takes no margin.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    WholeCheck checkWhole(PaperAccount account, PaperOrder order, List<BookSweep.Slice> slices) {
        UUID userId = account.userId();
        FuturesPosition position = margins.openPosition(userId, order.getPairId(), order.getPositionSide());
        BigDecimal toClose =
                closableBy(order.getSide(), position == null ? BigDecimal.ZERO : position.signedQuantity());
        if (order.reducing()) {
            // It opens nothing, so no margin and no mark.
            return new WholeCheck(
                    order.remainingQuantity().compareTo(toClose) > 0
                            ? Optional.of(FuturesArrival.NOT_FILLABLE)
                            : Optional.empty(),
                    Optional.empty());
        }
        Margining margining = marginingOf(userId, order);
        Optional<BigDecimal> mark = market.currentMarkPrice(margining.pair().symbol());
        if (!margining.isolated()) {
            return new WholeCheck(Optional.empty(), mark);
        }
        BigDecimal margin = BigDecimal.ZERO;
        for (BookSweep.Slice slice : slices) {
            BigDecimal closing = slice.quantity().min(toClose);
            toClose = toClose.subtract(closing);
            BigDecimal opening = slice.quantity().subtract(closing);
            if (opening.signum() > 0) {
                margin = margin.add(isolatedMargin(margining, order, opening, slice.price(), mark));
            }
        }
        return new WholeCheck(
                margin.compareTo(crossFreeCash(userId, order)) > 0
                        ? Optional.of(MARGIN_INSUFFICIENT)
                        : Optional.empty(),
                mark);
    }

    /**
     * The check of a FOK order before it sweeps.
     *
     * @param refusal why it cannot fill whole; empty when it can
     * @param mark the mark it was judged at, at which its slices take their margin; empty without one
     */
    record WholeCheck(Optional<String> refusal, Optional<BigDecimal> mark) {}

    /** The pair a fill of the order is margined on, and the Trader's settings of it. */
    private record Margining(PairListing pair, FuturesSymbolConfig config) {

        boolean isolated() {
            return config.getMarginType() == MarginType.ISOLATED;
        }
    }

    private Margining marginingOf(UUID userId, PaperOrder order) {
        PairListing pair = market.pairListings(List.of(order.getPairId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pair " + order.getPairId() + " is not stored"));
        return new Margining(pair, margins.symbolConfig(userId, pair.pairId(), market.leverageBrackets(pair.pairId())));
    }

    /** The isolated margin a fill of {@code opened} at {@code price} takes, with its open loss to the mark (4.3). */
    private BigDecimal isolatedMargin(
            Margining margining, PaperOrder order, BigDecimal opened, BigDecimal price, Optional<BigDecimal> mark) {
        return FuturesMarginMath.isolatedFillMargin(
                PositionDirection.openedBy(order.getSide()),
                opened,
                price,
                mark.orElse(price),
                margining.config().getLeverage());
    }

    /** The cross wallet's free cash an isolated fill is funded from, the order's own rest not counted as waiting. */
    private BigDecimal crossFreeCash(UUID userId, PaperOrder order) {
        return margins.crossState(userId, order.getId(), true).margin().crossFreeCash();
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
            Instant candleTime,
            CoinListing usdt,
            Optional<WholeCheck> whole) {
        UUID userId = account.userId();
        Margining margining = marginingOf(userId, order);
        PairListing pair = margining.pair();
        FuturesSymbolConfig config = margining.config();
        PositionDirection direction = PositionDirection.openedBy(order.getSide());
        BigDecimal margin = BigDecimal.ZERO;
        if (margining.isolated()) {
            // A FOK slice was funded whole beforehand, at the check's mark; any other fill is funded now.
            Optional<BigDecimal> mark = whole.isPresent() ? whole.get().mark() : market.currentMarkPrice(pair.symbol());
            margin = isolatedMargin(margining, order, opened, price, mark);
            if (whole.isEmpty() && margin.compareTo(crossFreeCash(userId, order)) > 0) {
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
                        order,
                        execution(
                                price, opened, liquidity, source, at, usdt, commissionOf(commission, order, liquidity)),
                        target.getId(),
                        BigDecimal.ZERO)
                .takenFrom(candleTime));
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
    private void trimReduceOnly(PaperAccount account, UUID pairId, PositionSide positionSide, Instant at) {
        UUID userId = account.userId();
        FuturesPosition position = margins.openPosition(userId, pairId, positionSide);
        BigDecimal size = position == null ? BigDecimal.ZERO : position.signedQuantity();
        if (position == null) {
            // A closed position takes its TP/SL with it: the closing conditional orders of the pair end (8.3 step 8).
            for (PaperOrder conditional : margins.openConditionalOrders(userId)) {
                if (conditional.getPairId().equals(pairId)
                        && conditional.getPositionSide() == positionSide
                        && (conditional.isClosePosition() || conditional.reducing())) {
                    end(account, conditional, POSITION_CLOSED, at);
                }
            }
        }
        List<PaperOrder> reduceOnly = new ArrayList<>();
        for (PaperOrder order : margins.openOrders(userId)) {
            if (order.getPairId().equals(pairId)
                    && order.getPositionSide() == positionSide
                    && order.reducing()
                    && order.isOpen()) {
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
        coverCrossDeficit(account, closed.getPairId(), Ref.to(LedgerRefType.POSITION, closed.getId()), usdt);
    }

    /**
     * With no cross position open, a cross wallet below zero is made good by an insurance cover named after the cash
     * flow that took it there (4.3 rule 5): a cross close, or a funding fee the cross wallet paid.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void coverCrossDeficit(PaperAccount account, UUID pairId, Ref cause, CoinListing usdt) {
        List<FuturesPosition> open = positions.findByUserIdAndPositionStatus(account.userId(), PositionStatus.OPEN);
        if (open.stream().anyMatch(position -> position.getMarginType() == MarginType.CROSSED)) {
            return;
        }
        BigDecimal crossWallet = margins.crossWallet(account.userId(), open);
        if (crossWallet.signum() < 0) {
            wallets.changeFutures(account, pairId, usdt, crossWallet.negate(), LedgerEntryType.INSURANCE_COVER, cause);
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

    /**
     * The commission of an ordinary fill, from {@link FuturesCommissionPolicy} (TR-44). Only zero is settled for now
     * (plan 2.3): a rate other than zero is refused here rather than taken without its FEE ledger row and its share of
     * the isolated cash flow, which the fee work adds with it.
     *
     * @return the commission rate, zero
     */
    static BigDecimal commissionOf(FuturesCommissionPolicy commission, PaperOrder order, Liquidity liquidity) {
        BigDecimal rate = commission.rate(order.getUserId(), order.getPairId(), liquidity);
        if (rate.signum() != 0) {
            throw new IllegalStateException("a Futures commission of " + rate.toPlainString()
                    + " is not settled yet (plan 2.3): FuturesCommissionPolicy must return zero");
        }
        return rate;
    }

    private static PaperFill.Execution execution(
            BigDecimal price,
            BigDecimal quantity,
            Liquidity liquidity,
            FillSource source,
            Instant at,
            CoinListing usdt,
            BigDecimal commissionRate) {
        return new PaperFill.Execution(
                price,
                quantity,
                Rounding.toAmount(price.multiply(quantity)),
                Rounding.toAmount(price.multiply(quantity).multiply(commissionRate)),
                usdt.coinId(),
                liquidity,
                source,
                at);
    }
}
