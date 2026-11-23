package com.cryptopilot.paper.service.impl;

import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.event.PaperOrderNotice;
import com.cryptopilot.paper.model.enums.OrderStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Which ended paper orders their Trader is told about, and as what (TR-29); see {@link PaperOrderNotice}.
 *
 * <ul>
 *   <li>FILLED without a reason: {@link PaperOrderNotice.Kind#FILLED}; with {@code TRIGGERED}, a conditional order
 *       that placed its order: {@link PaperOrderNotice.Kind#TRIGGERED}.
 *   <li>CANCELED or EXPIRED for one of the {@link #SYSTEM_REASONS}, all of which end an order that was waiting: as
 *       such. A cancel of the Trader (no reason), an end on arrival ({@code NOT_FILLABLE_ON_ARRIVAL},
 *       {@code POST_ONLY_WOULD_TAKE}, {@code WOULD_TRIGGER}) the Trader sees in the answer to the placement, and the
 *       OCO leg cancelled because the other executed ({@code OCO_OTHER_EXECUTED}), whose fill is told, are not.
 *   <li>Anything still working, partly filled or refused: nothing.
 *   <li>An order of a trading plan: nothing; the plan's notifications tell of it (NSF-07).
 * </ul>
 *
 * <p>Rule: TR-29; NSF-15.
 */
final class PaperOrderNotices {

    /** The reasons of the system that end a waiting order: told to the Trader. */
    static final Set<String> SYSTEM_REASONS = Set.of(
            PairStopCancellation.PAIR_NOT_TRADING,
            FuturesLiquidation.LIQUIDATION,
            FuturesSettlement.MARGIN_INSUFFICIENT,
            FuturesSettlement.POSITION_CLOSED,
            FuturesSettlement.REDUCE_ONLY,
            FuturesTrigger.NOTHING_TO_CLOSE,
            FuturesTrigger.MAX_POSITION,
            FuturesTrigger.MARK_STALE,
            FuturesTrigger.PERCENT_PRICE,
            FuturesOrderServiceImpl.GOOD_TILL_DATE,
            PaperOrderLists.INSUFFICIENT);

    private PaperOrderNotices() {}

    /** The notice of an order as its transaction leaves it, or empty when it is not one. */
    static Optional<PaperOrderNotice> of(PaperOrder order, String symbol) {
        if (order.getPlanId() != null || symbol == null) {
            return Optional.empty();
        }
        Optional<PaperOrderNotice.Kind> kind = kindOf(order.getOrderStatus(), order.getStatusReason());
        Instant at = order.getClosedAt();
        if (kind.isEmpty() || at == null) {
            return Optional.empty();
        }
        return Optional.of(new PaperOrderNotice(
                order.getUserId(),
                order.getId(),
                order.getMarketType(),
                symbol,
                order.getSide().name(),
                order.getOrderType().name(),
                kind.get(),
                kind.get() == PaperOrderNotice.Kind.CANCELED || kind.get() == PaperOrderNotice.Kind.EXPIRED
                        ? order.getStatusReason()
                        : null,
                order.getExecutedQuantity(),
                order.getAvgPrice(),
                at));
    }

    /** The kind of notice an order's end gives, if any. */
    static Optional<PaperOrderNotice.Kind> kindOf(OrderStatus status, String reason) {
        return switch (status) {
            case FILLED ->
                Optional.of(
                        FuturesTrigger.TRIGGERED.equals(reason)
                                ? PaperOrderNotice.Kind.TRIGGERED
                                : PaperOrderNotice.Kind.FILLED);
            case CANCELED -> isSystem(reason) ? Optional.of(PaperOrderNotice.Kind.CANCELED) : Optional.empty();
            case EXPIRED -> isSystem(reason) ? Optional.of(PaperOrderNotice.Kind.EXPIRED) : Optional.empty();
            case NEW, PENDING_NEW, PARTIALLY_FILLED, REJECTED -> Optional.empty();
        };
    }

    /** Whether the reason is the system's; a cancel of the Trader has none ({@code Set.of} refuses a null lookup). */
    private static boolean isSystem(String reason) {
        return reason != null && SYSTEM_REASONS.contains(reason);
    }
}
