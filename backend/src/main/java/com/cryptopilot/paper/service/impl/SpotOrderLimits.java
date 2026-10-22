package com.cryptopilot.paper.service.impl;

import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.market.PairFilters;
import com.cryptopilot.paper.config.PaperOrderProperties;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import com.cryptopilot.paper.repository.PaperOrderRepository;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many orders an account may have open on one Spot pair ({@code MAX_NUM_ORDERS}) and how many of them may be stops
 * ({@code MAX_NUM_ALGO_ORDERS}). The orders of groups count, the pending ones too, as they are placed in full once
 * their working order fills. A pair the exchange sent no limit for takes the configured defaults, so no book of the
 * matching engine grows without bound.
 *
 * <p>Counted and then added to under the Trader's paper lock, which the caller holds: two placements cannot both see
 * room for one more order.
 *
 * <p>Rule: TR-02, TR-10; D-63.
 *
 * <p>Reference: Binance Spot API documentation, "Filters" (MAX_NUM_ORDERS, MAX_NUM_ALGO_ORDERS).
 * <p>Reference: Kleppmann, M. (2017). <i>Designing Data-Intensive Applications</i>. O'Reilly, ch. 7 (a check-then-act
 * on a set of rows is write skew unless it is serialised).
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class SpotOrderLimits {

    /** The statuses of an order that counts: working, or waiting in a group to be placed. */
    private static final Set<OrderStatus> COUNTED =
            EnumSet.of(OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_NEW);

    private static final Set<OrderType> STOPS = EnumSet.of(
            OrderType.STOP_LOSS, OrderType.STOP_LOSS_LIMIT, OrderType.TAKE_PROFIT, OrderType.TAKE_PROFIT_LIMIT);

    private final PaperOrderRepository orders;
    private final PaperOrderProperties properties;

    /**
     * Refuses a placement that would leave the account more open orders or stops on the pair than allowed.
     *
     * @param adding how many orders the placement adds
     * @param addingStops how many of them are stops
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRoom(UUID accountId, UUID pairId, PairFilters filters, int adding, int addingStops) {
        Integer maxOrders = filters.rules().maxNumOrders();
        int ordersLimit = maxOrders != null ? maxOrders : properties.spotMaxNumOrders();
        long open = orders.countByAccountIdAndPairIdAndOrderStatusIn(accountId, pairId, COUNTED);
        if (open + adding > ordersLimit) {
            throw refused(ErrorCode.PAPER_MAX_NUM_ORDERS, "orders", open + adding, ordersLimit);
        }
        if (addingStops == 0) {
            return;
        }
        Integer maxStops = filters.rules().maxNumAlgoOrders();
        int stopsLimit = maxStops != null ? maxStops : properties.spotMaxNumAlgoOrders();
        long stops = orders.countByAccountIdAndPairIdAndOrderStatusInAndOrderTypeIn(accountId, pairId, COUNTED, STOPS);
        if (stops + addingStops > stopsLimit) {
            throw refused(ErrorCode.PAPER_MAX_NUM_ALGO_ORDERS, "stopOrders", stops + addingStops, stopsLimit);
        }
    }

    private static BusinessException refused(ErrorCode code, String field, long count, int limit) {
        return new BusinessException(
                code,
                count + " open " + field + " on the pair would be more than the " + limit + " allowed",
                field,
                count,
                limit,
                SpotFilters.MAX);
    }
}
