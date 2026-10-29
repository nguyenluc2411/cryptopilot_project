package com.cryptopilot.paper.calculator;

import com.cryptopilot.common.util.Rounding;
import com.cryptopilot.market.LeverageTier;
import com.cryptopilot.paper.model.FuturesAccountMargin;
import com.cryptopilot.paper.model.IsolatedCashFlow;
import com.cryptopilot.paper.model.PendingOrderMargin;
import com.cryptopilot.paper.model.PositionFill;
import com.cryptopilot.paper.model.enums.OrderSide;
import com.cryptopilot.paper.model.enums.PositionDirection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The arithmetic of paper Futures USDⓈ-M, fees excluded: margin, profit and loss, the cost of an order, the derived
 * balances of the account, the liquidation price and the isolated cash-flow rules. Pure functions of their arguments,
 * shared by placement, matching, liquidation and funding so that every path computes the same numbers; the web client
 * runs the same golden cases against its own copy.
 *
 * <p>Amounts are rounded to the amount scale half-even, prices to 12 decimals; intermediate divisions use
 * {@link Rounding#DIVISION}. The paper module keeps its own copy of the bracket lookup rather than calling the plan
 * calculator, because the two engines stay apart until they are merged (D-117).
 *
 * <p>Rule: TR-15 … TR-18, TR-22, TR-24, TR-40; BR-26, BR-27; D-127; A-01.
 * <p>Reference: Binance. <i>USDⓈ-M Futures: How to Calculate Liquidation Price</i>, <i>Leverage and Margin of
 * USDⓈ-M Futures</i>, <i>How to Calculate Cost Required to Open a Position in Perpetual Futures Contracts</i> and
 * <i>Introduction to Binance Futures Funding Rates</i>, Binance Support.
 * <p>Reference: Hull, J. C. (2022). <i>Options, Futures, and Other Derivatives</i> (11th ed.). Pearson, ch. 2
 * (margin accounts and the maintenance margin).
 */
public final class FuturesMarginMath {

    /** Scale of a stored price or entry price (NUMERIC(28,12)). */
    public static final int PRICE_SCALE = 12;

    /**
     * The buffer over the last price a MARKET order (and a short LIMIT order) is costed at, 0.15 %, so that the fill
     * a moment later is still covered.
     */
    static final BigDecimal MARKET_PRICE_BUFFER = new BigDecimal("0.0015");

    private FuturesMarginMath() {}

    // ------------------------------------------------------------------ brackets, IM, MM

    /**
     * The brackets in notional order, each with a maintenance amount: the one stored, or else the amount that makes
     * the maintenance margin meet the bracket below at its floor, {@code cum_1 = 0} and
     * {@code cum_n = cum_(n-1) + floor_n × (MMR_n − MMR_(n-1))}.
     *
     * <p>Rule: BR-26, BR-27; A-01.
     */
    public static List<LeverageTier> withMaintenanceAmounts(List<LeverageTier> tiers) {
        List<LeverageTier> sorted = tiers.stream()
                .sorted(Comparator.comparing(LeverageTier::notionalFloor))
                .toList();
        List<LeverageTier> resolved = new ArrayList<>(sorted.size());
        LeverageTier below = null;
        for (LeverageTier tier : sorted) {
            if (tier.maintenanceAmount() == null) {
                BigDecimal amount = below == null
                        ? BigDecimal.ZERO
                        : below.maintenanceAmount()
                                .add(tier.notionalFloor()
                                        .multiply(
                                                tier.maintenanceMarginRate().subtract(below.maintenanceMarginRate())));
                tier = new LeverageTier(
                        tier.bracketNo(),
                        tier.notionalFloor(),
                        tier.notionalCap(),
                        tier.maxLeverage(),
                        tier.maintenanceMarginRate(),
                        amount);
            }
            resolved.add(tier);
            below = tier;
        }
        return resolved;
    }

    /**
     * The bracket whose range {@code [floor, cap)} contains the notional, with its maintenance amount, or empty when
     * the notional is above the largest bracket.
     *
     * <p>Rule: BR-26, BR-27.
     */
    public static Optional<LeverageTier> bracketFor(List<LeverageTier> tiers, BigDecimal notional) {
        BigDecimal size = notional.abs();
        return withMaintenanceAmounts(tiers).stream()
                .filter(t -> size.compareTo(t.notionalFloor()) >= 0 && size.compareTo(t.notionalCap()) < 0)
                .findFirst();
    }

    /**
     * The maintenance margin of a notional, {@code notional × MMR − cum} of its bracket.
     *
     * <p>Rule: BR-27.
     *
     * @throws IllegalArgumentException if the notional is above the largest bracket
     */
    public static BigDecimal maintenanceMargin(List<LeverageTier> tiers, BigDecimal notional) {
        LeverageTier tier = bracketFor(tiers, notional)
                .orElseThrow(() -> new IllegalArgumentException("notional " + notional + " is above every bracket"));
        return Rounding.toAmount(
                notional.abs().multiply(tier.maintenanceMarginRate()).subtract(tier.maintenanceAmount()));
    }

    /**
     * The maintenance margin a liquidation is judged by: {@code |notional| × MMR − cum} of the notional's bracket, the
     * brackets' maintenance amounts filled in, the largest bracket's above every cap; not rounded.
     *
     * <p>Rule: TR-22, TR-23; BR-27.
     */
    public static BigDecimal liquidationMaintenance(List<LeverageTier> tiers, BigDecimal notional) {
        List<LeverageTier> resolved = withMaintenanceAmounts(tiers);
        LeverageTier tier = bracketFor(resolved, notional).orElse(resolved.getLast());
        return notional.abs().multiply(tier.maintenanceMarginRate()).subtract(tier.maintenanceAmount());
    }

    /**
     * The initial margin of a notional at a leverage, {@code notional / L}.
     *
     * <p>Rule: BR-26.
     */
    public static BigDecimal initialMargin(BigDecimal notional, int leverage) {
        return Rounding.toAmount(Rounding.divide(notional.abs(), BigDecimal.valueOf(requireLeverage(leverage))));
    }

    /**
     * The highest leverage of the pair: the maximum of bracket 1, the bracket of the smallest notionals.
     *
     * <p>Rule: BR-26.
     */
    public static int maxLeverage(List<LeverageTier> tiers) {
        return withMaintenanceAmounts(tiers).stream()
                .findFirst()
                .map(LeverageTier::maxLeverage)
                .orElseThrow(() -> new IllegalArgumentException("the pair has no leverage bracket"));
    }

    /**
     * The largest notional a position may reach at a leverage: the highest cap of the brackets that allow it, the
     * "maximum position at current leverage" of the exchange; zero when no bracket allows it.
     *
     * <p>Rule: BR-26.
     */
    public static BigDecimal maxNotionalAtLeverage(List<LeverageTier> tiers, int leverage) {
        return tiers.stream()
                .filter(t -> t.maxLeverage() >= leverage)
                .map(LeverageTier::notionalCap)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ profit and loss

    /** The unrealized profit of a position at the mark price, {@code side × Q × (M − E)}. */
    public static BigDecimal unrealizedPnl(
            PositionDirection direction, BigDecimal quantity, BigDecimal entryPrice, BigDecimal markPrice) {
        return Rounding.toAmount(direction.sign().multiply(quantity).multiply(markPrice.subtract(entryPrice)));
    }

    /**
     * What one fill does to a one-way position: open, increase (the entry becomes the size-weighted average), reduce
     * (the entry stays), close, or reverse (close all, then open the rest the other way at the fill price).
     *
     * <p>Rule: TR-16; D-127.
     *
     * @param quantityBefore the signed size before the fill, positive for long, negative for short, zero when flat
     * @param entryPriceBefore the entry price before the fill, ignored when flat
     * @param side the side of the fill
     * @param quantity the filled quantity, positive
     * @param price the fill price, positive
     */
    public static PositionFill applyFill(
            BigDecimal quantityBefore,
            BigDecimal entryPriceBefore,
            OrderSide side,
            BigDecimal quantity,
            BigDecimal price) {
        requirePositive(quantity, "quantity");
        requirePositive(price, "price");
        BigDecimal fillSign = PositionDirection.openedBy(side).sign();
        BigDecimal size = quantityBefore.abs();
        if (quantityBefore.signum() == 0) {
            return new PositionFill(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO.setScale(Rounding.AMOUNT_SCALE),
                    quantity,
                    fillSign.multiply(quantity),
                    toPrice(price));
        }
        if (quantityBefore.signum() == fillSign.signum()) {
            BigDecimal entry =
                    Rounding.divide(size.multiply(entryPriceBefore).add(quantity.multiply(price)), size.add(quantity));
            return new PositionFill(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO.setScale(Rounding.AMOUNT_SCALE),
                    quantity,
                    quantityBefore.add(fillSign.multiply(quantity)),
                    toPrice(entry));
        }
        PositionDirection held = quantityBefore.signum() > 0 ? PositionDirection.LONG : PositionDirection.SHORT;
        BigDecimal closed = quantity.min(size);
        BigDecimal realized =
                Rounding.toAmount(held.sign().multiply(closed).multiply(price.subtract(entryPriceBefore)));
        int against = quantity.compareTo(size);
        if (against < 0) {
            return new PositionFill(
                    closed,
                    realized,
                    BigDecimal.ZERO,
                    quantityBefore.add(fillSign.multiply(quantity)),
                    entryPriceBefore);
        }
        if (against == 0) {
            return new PositionFill(closed, realized, BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
        BigDecimal opened = quantity.subtract(size);
        return new PositionFill(closed, realized, opened, fillSign.multiply(opened), toPrice(price));
    }

    // ------------------------------------------------------------------ cost, order margin, balances

    /**
     * The cost of opening a quantity: initial margin at the assumed price plus the open loss against the mark price.
     * A LIMIT order (a triggered STOP too) is costed at its price when long and at
     * {@code max(last × 1.0015, mark, price)} when short; a MARKET order at {@code last × 1.0015} either way.
     *
     * <p>Rule: TR-15; D-127.
     *
     * @param side the order side
     * @param quantity the quantity, positive
     * @param limitPrice the limit price, or {@code null} for a MARKET order
     * @param lastPrice the last traded price
     * @param markPrice the mark price
     * @param leverage the leverage of the pair
     */
    public static BigDecimal orderCost(
            OrderSide side,
            BigDecimal quantity,
            BigDecimal limitPrice,
            BigDecimal lastPrice,
            BigDecimal markPrice,
            int leverage) {
        requirePositive(quantity, "quantity");
        BigDecimal sign = PositionDirection.openedBy(side).sign();
        BigDecimal buffered = lastPrice.multiply(BigDecimal.ONE.add(MARKET_PRICE_BUFFER));
        BigDecimal assumingPrice;
        BigDecimal lossReference;
        if (limitPrice == null) {
            assumingPrice = buffered;
            lossReference = buffered;
        } else {
            assumingPrice =
                    side == OrderSide.BUY ? limitPrice : buffered.max(markPrice).max(limitPrice);
            lossReference = limitPrice;
        }
        BigDecimal initial =
                Rounding.divide(assumingPrice.multiply(quantity), BigDecimal.valueOf(requireLeverage(leverage)));
        BigDecimal openLoss = openLoss(sign, quantity, markPrice, lossReference);
        return Rounding.toAmount(initial.add(openLoss));
    }

    /**
     * The margin a fill moves into an isolated wallet when it opens or increases the position, priced at the fill:
     * {@code q × P / L + q × |min(0, side × (M − P))|}.
     *
     * <p>Rule: TR-16; D-127.
     */
    public static BigDecimal isolatedFillMargin(
            PositionDirection direction,
            BigDecimal quantity,
            BigDecimal fillPrice,
            BigDecimal markPrice,
            int leverage) {
        BigDecimal initial =
                Rounding.divide(quantity.multiply(fillPrice), BigDecimal.valueOf(requireLeverage(leverage)));
        return Rounding.toAmount(initial.add(openLoss(direction.sign(), quantity, markPrice, fillPrice)));
    }

    /**
     * The order margin of one pair in one-way mode: per side, the cost of the part of the open orders that would
     * increase the position (the part that would first close the opposite position costs nothing, taken
     * proportionally), and the larger of the two sides, since the price can only move one way at a time.
     *
     * <p>Rule: TR-15; D-127.
     *
     * @param positionQuantity the signed size of the pair's position, zero when flat
     * @param orders the open orders of the pair
     */
    public static BigDecimal openOrderInitialMargin(BigDecimal positionQuantity, List<PendingOrderMargin> orders) {
        BigDecimal bid =
                sideMargin(orders, OrderSide.BUY, positionQuantity.signum() < 0 ? positionQuantity.abs() : null);
        BigDecimal ask = sideMargin(orders, OrderSide.SELL, positionQuantity.signum() > 0 ? positionQuantity : null);
        return Rounding.toAmount(bid.max(ask));
    }

    /**
     * The derived balances of the cross wallet:
     * {@code available = max(0, cross wallet + cross uPnL − cross IM − order margin)},
     * {@code crossFreeCash = max(0, cross wallet − cross IM − order margin + min(0, cross uPnL))} and
     * {@code maxWithdraw = min(available, crossFreeCash)}. Unrealized profit can open orders but neither funds an
     * isolated wallet nor leaves the account.
     *
     * <p>Rule: TR-15, TR-18; D-127.
     */
    public static FuturesAccountMargin accountMargin(
            BigDecimal crossWalletBalance,
            BigDecimal crossUnrealizedPnl,
            BigDecimal crossPositionInitialMargin,
            BigDecimal openOrderInitialMargin) {
        BigDecimal committed = crossPositionInitialMargin.add(openOrderInitialMargin);
        BigDecimal available =
                crossWalletBalance.add(crossUnrealizedPnl).subtract(committed).max(BigDecimal.ZERO);
        BigDecimal freeCash = crossWalletBalance
                .subtract(committed)
                .add(crossUnrealizedPnl.min(BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
        return new FuturesAccountMargin(
                Rounding.toAmount(available), Rounding.toAmount(freeCash), Rounding.toAmount(available.min(freeCash)));
    }

    /**
     * The largest quantity an order can open with a budget, rounded down to the step: its cost fits the budget
     * ({@code availableBalance} for a cross pair, {@code crossFreeCash} for an isolated one) and the position after
     * the fill stays within the maximum notional at the leverage. An opposite position is closed first for free.
     *
     * <p>Rule: TR-15, TR-25; D-127.
     *
     * @param side the order side
     * @param limitPrice the limit price, or {@code null} for a MARKET order
     * @param lastPrice the last traded price
     * @param markPrice the mark price
     * @param leverage the leverage of the pair
     * @param budget the balance the cost is paid from
     * @param positionQuantity the signed size of the pair's position, zero when flat
     * @param maxNotional the maximum notional at the leverage
     * @param stepSize the quantity step of the pair
     */
    public static BigDecimal maxOpenQuantity(
            OrderSide side,
            BigDecimal limitPrice,
            BigDecimal lastPrice,
            BigDecimal markPrice,
            int leverage,
            BigDecimal budget,
            BigDecimal positionQuantity,
            BigDecimal maxNotional,
            BigDecimal stepSize) {
        BigDecimal sign = PositionDirection.openedBy(side).sign();
        boolean opposite = positionQuantity.signum() != 0 && positionQuantity.signum() != sign.signum();
        BigDecimal closable = opposite ? positionQuantity.abs() : BigDecimal.ZERO;
        BigDecimal held = opposite ? BigDecimal.ZERO : positionQuantity.abs();
        BigDecimal unitCost = orderCost(side, BigDecimal.ONE, limitPrice, lastPrice, markPrice, leverage);
        BigDecimal notionalPrice =
                limitPrice != null ? limitPrice : lastPrice.multiply(BigDecimal.ONE.add(MARKET_PRICE_BUFFER));
        BigDecimal byBudget = Rounding.divide(budget.max(BigDecimal.ZERO), unitCost);
        BigDecimal byNotional =
                Rounding.divide(maxNotional, notionalPrice).subtract(held).max(BigDecimal.ZERO);
        return Rounding.toStepSize(closable.add(byBudget.min(byNotional)), stepSize);
    }

    /**
     * How much margin can be taken out of an isolated position:
     * {@code max(0, min(IW, IW + uPnL − notional at mark / L))}, so the rest still covers the initial margin at the
     * mark, the open loss included.
     *
     * <p>Rule: TR-17; D-127.
     */
    public static BigDecimal maxRemovableMargin(
            BigDecimal isolatedWallet, BigDecimal unrealizedPnl, BigDecimal markNotional, int leverage) {
        BigDecimal needed = Rounding.divide(markNotional.abs(), BigDecimal.valueOf(requireLeverage(leverage)));
        return Rounding.toAmount(isolatedWallet
                .min(isolatedWallet.add(unrealizedPnl).subtract(needed))
                .max(BigDecimal.ZERO));
    }

    /**
     * The margin ratio, maintenance margin over margin balance, or empty when the margin balance is zero or negative
     * (the position is past its liquidation price). Liquidation is due at a ratio of 1 or more.
     *
     * <p>Rule: TR-18, TR-22; D-127.
     */
    public static Optional<BigDecimal> marginRatio(BigDecimal maintenanceMargin, BigDecimal marginBalance) {
        return marginBalance.signum() <= 0
                ? Optional.empty()
                : Optional.of(Rounding.divide(maintenanceMargin, marginBalance));
    }

    // ------------------------------------------------------------------ liquidation price

    /**
     * The liquidation price of a one-way position, where the margin balance of its scope meets its maintenance
     * margin: {@code LP = (WB − TMM + UPNL + cum − side × Q × E) / (Q × (MMR − side))}. Isolated: {@code WB} is the
     * isolated wallet and the other two are zero; cross: {@code WB} is the cross wallet and {@code TMM}, {@code UPNL}
     * the maintenance margin and unrealized profit of the other cross positions. The bracket is the one of the
     * notional at the liquidation price, found again when the first guess lands in another bracket. Rounded to the
     * tick towards the position (long up, short down); zero when no price can liquidate it.
     *
     * <p>Rule: TR-18, TR-22; BR-27; D-127; A-01.
     */
    public static BigDecimal liquidationPrice(
            PositionDirection direction,
            BigDecimal quantity,
            BigDecimal entryPrice,
            BigDecimal walletBalance,
            BigDecimal otherMaintenanceMargin,
            BigDecimal otherUnrealizedPnl,
            List<LeverageTier> tiers,
            BigDecimal tickSize) {
        requirePositive(quantity, "quantity");
        BigDecimal side = direction.sign();
        BigDecimal base = walletBalance
                .subtract(otherMaintenanceMargin)
                .add(otherUnrealizedPnl)
                .subtract(side.multiply(quantity).multiply(entryPrice));
        List<LeverageTier> resolved = withMaintenanceAmounts(tiers);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("the pair has no leverage bracket");
        }
        LeverageTier tier = bracketFor(resolved, quantity.multiply(entryPrice)).orElse(resolved.getLast());
        BigDecimal raw = BigDecimal.ZERO;
        for (int attempt = 0; attempt <= resolved.size(); attempt++) {
            BigDecimal denominator =
                    quantity.multiply(tier.maintenanceMarginRate().subtract(side));
            raw = Rounding.divide(base.add(tier.maintenanceAmount()), denominator);
            if (raw.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            LeverageTier atPrice = bracketFor(resolved, quantity.multiply(raw)).orElse(resolved.getLast());
            if (atPrice.bracketNo() == tier.bracketNo()) {
                break;
            }
            tier = atPrice;
        }
        return Rounding.toTickSize(
                raw, tickSize, direction == PositionDirection.LONG ? RoundingMode.CEILING : RoundingMode.FLOOR);
    }

    // ------------------------------------------------------------------ isolated cash flow, funding, liquidation

    /**
     * Rule (A) of an isolated position's closing event, a reduction, a full close or a liquidation, with its fee as
     * one event: {@code r = realized − fee}, {@code rel} the released margin ({@code IW × q / Q}, or all of it).
     * {@code rel + r ≥ 0}: the wallet releases {@code rel} and the cross wallet gets {@code rel + r};
     * {@code IW + r ≥ 0}: the loss is taken from the isolated wallet; otherwise the isolated wallet goes to zero and
     * the excess is an insurance cover. The loss of an isolated position never reaches the cross wallet.
     *
     * <p>Rule: TR-16, TR-22; D-127.
     *
     * @param isolatedWallet the isolated wallet before the event, zero or positive
     * @param closedQuantity the quantity closed, positive
     * @param positionQuantity the size before the event, at least {@code closedQuantity}
     * @param realizedPnl the realized profit or loss of the closed quantity
     * @param fee the commission or the liquidation fee of the event, zero or positive
     */
    public static IsolatedCashFlow closeFlow(
            BigDecimal isolatedWallet,
            BigDecimal closedQuantity,
            BigDecimal positionQuantity,
            BigDecimal realizedPnl,
            BigDecimal fee) {
        requirePositive(closedQuantity, "closedQuantity");
        if (closedQuantity.compareTo(positionQuantity.abs()) > 0) {
            throw new IllegalArgumentException(
                    "closed " + closedQuantity + " is more than the position " + positionQuantity);
        }
        BigDecimal released = closedQuantity.compareTo(positionQuantity.abs()) == 0
                ? isolatedWallet
                : Rounding.toAmount(Rounding.divide(isolatedWallet.multiply(closedQuantity), positionQuantity.abs()));
        BigDecimal result = realizedPnl.subtract(fee);
        if (released.add(result).signum() >= 0) {
            return flow(IsolatedCashFlow.Branch.RELEASE, released.negate(), released.add(result), BigDecimal.ZERO);
        }
        if (isolatedWallet.add(result).signum() >= 0) {
            return flow(IsolatedCashFlow.Branch.LOSS_ABSORB, result, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return flow(
                IsolatedCashFlow.Branch.INSURANCE,
                isolatedWallet.negate(),
                BigDecimal.ZERO,
                isolatedWallet.add(result).negate());
    }

    /**
     * Rule (B) of funding on an isolated position: received or paid, the fee goes into the isolated wallet; a payment
     * larger than the wallet takes it to zero and the excess is an insurance cover. The cross wallet never changes.
     *
     * <p>Rule: TR-24; D-127.
     */
    public static IsolatedCashFlow fundingFlow(BigDecimal isolatedWallet, BigDecimal fundingFee) {
        if (isolatedWallet.add(fundingFee).signum() >= 0) {
            return flow(IsolatedCashFlow.Branch.RELEASE, fundingFee, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return flow(
                IsolatedCashFlow.Branch.INSURANCE,
                isolatedWallet.negate(),
                BigDecimal.ZERO,
                isolatedWallet.add(fundingFee).negate());
    }

    /**
     * The funding fee of a position at a settlement, signed for the holder: {@code −side × Q × mark × rate}. A
     * positive rate makes longs pay and shorts receive.
     *
     * <p>Rule: TR-24; BR-11.
     */
    public static BigDecimal fundingFee(
            PositionDirection direction, BigDecimal quantity, BigDecimal markPrice, BigDecimal fundingRate) {
        return Rounding.toAmount(
                direction.sign().negate().multiply(quantity).multiply(markPrice).multiply(fundingRate));
    }

    /**
     * The clearance fee of a liquidation, {@code min(notional × rate, what is left)}: never more than the margin
     * balance the closed position leaves, so the fee alone never creates a loss beyond the deposit.
     *
     * <p>Rule: TR-22; D-97.
     *
     * @param notional the notional of the closed position at the liquidation price
     * @param feeRate the liquidation fee rate of the pair
     * @param remaining the margin balance left after the realized loss
     */
    public static BigDecimal liquidationFee(BigDecimal notional, BigDecimal feeRate, BigDecimal remaining) {
        return Rounding.toAmount(notional.abs().multiply(feeRate).min(remaining.max(BigDecimal.ZERO)));
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal openLoss(BigDecimal sign, BigDecimal quantity, BigDecimal markPrice, BigDecimal price) {
        return quantity.multiply(
                sign.multiply(markPrice.subtract(price)).min(BigDecimal.ZERO).abs());
    }

    private static BigDecimal sideMargin(List<PendingOrderMargin> orders, OrderSide side, BigDecimal closable) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (PendingOrderMargin order : orders) {
            if (order.side() == side) {
                quantity = quantity.add(order.quantity());
                cost = cost.add(order.cost());
            }
        }
        if (quantity.signum() == 0 || closable == null) {
            return cost;
        }
        BigDecimal opening = quantity.subtract(closable).max(BigDecimal.ZERO);
        return Rounding.divide(cost.multiply(opening), quantity);
    }

    private static IsolatedCashFlow flow(
            IsolatedCashFlow.Branch branch, BigDecimal walletChange, BigDecimal crossChange, BigDecimal cover) {
        return new IsolatedCashFlow(
                branch, Rounding.toAmount(walletChange), Rounding.toAmount(crossChange), Rounding.toAmount(cover));
    }

    private static BigDecimal toPrice(BigDecimal price) {
        return price.setScale(PRICE_SCALE, RoundingMode.HALF_EVEN);
    }

    private static int requireLeverage(int leverage) {
        if (leverage < 1) {
            throw new IllegalArgumentException("leverage must be at least 1, was " + leverage);
        }
        return leverage;
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
