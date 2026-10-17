package com.cryptopilot.paper.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.model.enums.OrderListStatus;
import com.cryptopilot.paper.model.enums.OrderListType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * A group of paper orders placed together on one pair (TR-10): an OCO, an OTO or an OTOCO. Its orders point at it
 * through {@code paper_order.list_id}; it is EXECUTING while one of them works or waits to be placed, then ALL_DONE.
 *
 * <p>Rule: TR-10.
 */
@Getter
@Entity
@Table(name = "paper_order_list")
@AttributeOverride(name = "id", column = @Column(name = "list_id", nullable = false, updatable = false))
public class PaperOrderList extends BaseEntity {

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "pair_id", nullable = false, updatable = false)
    private UUID pairId;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 32, updatable = false)
    private MarketType marketType;

    @Enumerated(EnumType.STRING)
    @Column(name = "list_type", nullable = false, length = 32, updatable = false)
    private OrderListType listType;

    @Enumerated(EnumType.STRING)
    @Column(name = "list_status", nullable = false, length = 32)
    private OrderListStatus listStatus;

    /** For JPA only. */
    protected PaperOrderList() {}

    private PaperOrderList(UUID accountId, UUID pairId, OrderListType listType) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.pairId = Objects.requireNonNull(pairId, "pairId must not be null");
        this.marketType = MarketType.SPOT;
        this.listType = Objects.requireNonNull(listType, "listType must not be null");
        this.listStatus = OrderListStatus.EXECUTING;
    }

    /** A Spot group of orders, EXECUTING. */
    public static PaperOrderList spot(UUID accountId, UUID pairId, OrderListType listType) {
        return new PaperOrderList(accountId, pairId, listType);
    }

    /** Whether one of its orders still works or waits to be placed. */
    public boolean isExecuting() {
        return listStatus == OrderListStatus.EXECUTING;
    }

    /** Records that every one of its orders has finished. */
    public void done() {
        listStatus = OrderListStatus.ALL_DONE;
    }
}
