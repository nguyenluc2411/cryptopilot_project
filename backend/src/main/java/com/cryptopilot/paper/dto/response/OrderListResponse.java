package com.cryptopilot.paper.dto.response;

import com.cryptopilot.paper.model.enums.OrderListStatus;
import com.cryptopilot.paper.model.enums.OrderListType;
import java.util.List;
import java.util.UUID;

/**
 * A group of paper orders of the caller (TR-10).
 *
 * @param listId the group
 * @param type OCO, OTO or OTOCO
 * @param status EXECUTING while one of its orders works or waits to be placed, then ALL_DONE
 * @param pairId the pair
 * @param symbol the pair's symbol
 * @param orders its orders: the working one first for an OTO or OTOCO, then the take profit and the stop
 */
public record OrderListResponse(
        UUID listId,
        OrderListType type,
        OrderListStatus status,
        UUID pairId,
        String symbol,
        List<OrderResponse> orders) {}
