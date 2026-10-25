package com.cryptopilot.paper.repository;

import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.TriggerOrder;
import com.cryptopilot.paper.model.enums.OrderStatus;
import com.cryptopilot.paper.model.enums.OrderType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gateway to paper orders. Every lookup a Trader causes names the account, so another Trader's order is never
 * loaded; only the matching engine reads across accounts.
 *
 * <p>Rule: TR-02.
 */
public interface PaperOrderRepository extends Repository<PaperOrder, UUID> {

    /** An order by its key, whoever owns it: for the matching engine, which checks the account itself. */
    @Transactional(readOnly = true)
    Optional<PaperOrder> findById(UUID orderId);

    /** The account's order with this key, or empty. */
    @Transactional(readOnly = true)
    Optional<PaperOrder> findByIdAndUserId(UUID orderId, UUID userId);

    /** The account's order with this idempotency key, or empty. */
    @Transactional(readOnly = true)
    Optional<PaperOrder> findByUserIdAndClientOrderId(UUID userId, String clientOrderId);

    /** The account's orders in these statuses, newest first. */
    @Transactional(readOnly = true)
    List<PaperOrder> findByUserIdAndOrderStatusInOrderByCreatedAtDescIdDesc(
            UUID userId, Collection<OrderStatus> statuses);

    /** The account's orders of one pair in these statuses, oldest first. */
    List<PaperOrder> findByUserIdAndPairIdAndOrderStatusInOrderByCreatedAtAscIdAsc(
            UUID userId, UUID pairId, Collection<OrderStatus> statuses);

    /** The account's orders of one pair on one market in these statuses, oldest first. */
    List<PaperOrder> findByUserIdAndPairIdAndMarketTypeAndOrderStatusInOrderByCreatedAtAscIdAsc(
            UUID userId, UUID pairId, MarketType marketType, Collection<OrderStatus> statuses);

    /**
     * One page of an account's orders in these statuses, placed in {@code [from, to)}, newest first. No parameter is
     * null, so PostgreSQL can type every one.
     */
    @Transactional(readOnly = true)
    @Query(
            value = "select o from PaperOrder o where o.userId = :userId and o.orderStatus in :statuses"
                    + " and o.createdAt >= :from and o.createdAt < :to order by o.createdAt desc, o.id desc",
            countQuery = "select count(o) from PaperOrder o where o.userId = :userId"
                    + " and o.orderStatus in :statuses and o.createdAt >= :from and o.createdAt < :to")
    Page<PaperOrder> search(UUID userId, Collection<OrderStatus> statuses, Instant from, Instant to, Pageable pageable);

    /** As {@link #search}, for one pair. */
    @Transactional(readOnly = true)
    @Query(
            value = "select o from PaperOrder o where o.userId = :userId and o.pairId = :pairId"
                    + " and o.orderStatus in :statuses and o.createdAt >= :from and o.createdAt < :to"
                    + " order by o.createdAt desc, o.id desc",
            countQuery = "select count(o) from PaperOrder o where o.userId = :userId and o.pairId = :pairId"
                    + " and o.orderStatus in :statuses and o.createdAt >= :from and o.createdAt < :to")
    Page<PaperOrder> searchPair(
            UUID userId, UUID pairId, Collection<OrderStatus> statuses, Instant from, Instant to, Pageable pageable);

    /**
     * Every working order of every account that waits at its limit price, which the matching engine starts its books
     * from: LIMIT and LIMIT_MAKER orders, and STOP_LOSS_LIMIT and TAKE_PROFIT_LIMIT orders that have triggered,
     * waiting since then.
     */
    @Transactional(readOnly = true)
    @Query("select new com.cryptopilot.paper.model.RestingOrder(o.id, o.userId, o.marketType, o.pairId, o.side,"
            + " o.limitPrice, coalesce(o.triggeredAt, o.activatedAt, o.createdAt)) from PaperOrder o"
            + " where o.orderStatus in :statuses and (o.orderType in (com.cryptopilot.paper.model.enums.OrderType.LIMIT,"
            + " com.cryptopilot.paper.model.enums.OrderType.LIMIT_MAKER) or (o.orderType in"
            + " (com.cryptopilot.paper.model.enums.OrderType.STOP_LOSS_LIMIT,"
            + " com.cryptopilot.paper.model.enums.OrderType.TAKE_PROFIT_LIMIT) and o.triggeredAt is not null))")
    List<RestingOrder> findResting(Collection<OrderStatus> statuses);

    /**
     * Every Spot stop that still waits for its trigger, which the engine starts watching from: a pending stop of a
     * group from its activation, the others from their placement (D-77).
     */
    @Transactional(readOnly = true)
    @Query(
            "select new com.cryptopilot.paper.model.TriggerOrder(o.id, o.userId, o.marketType, o.pairId, o.side,"
                    + " o.triggerCondition, o.stopPrice, o.callbackRate, o.trailingExtremePrice,"
                    + " coalesce(o.activatedAt, o.createdAt)) from PaperOrder o"
                    + " where o.orderStatus in :statuses and o.triggeredAt is null and o.orderType in"
                    + " (com.cryptopilot.paper.model.enums.OrderType.STOP_LOSS, com.cryptopilot.paper.model.enums.OrderType.STOP_LOSS_LIMIT,"
                    + " com.cryptopilot.paper.model.enums.OrderType.TAKE_PROFIT, com.cryptopilot.paper.model.enums.OrderType.TAKE_PROFIT_LIMIT)")
    List<TriggerOrder> findAwaitingTrigger(Collection<OrderStatus> statuses);

    /** The orders of a group, as they were placed. */
    @Transactional(readOnly = true)
    List<PaperOrder> findByListIdOrderByClientOrderIdAsc(UUID listId);

    /** Reads the orders of these ids. */
    List<PaperOrder> findByIdIn(Collection<UUID> ids);

    /** How many of the account's orders of one pair on one market are in these statuses. */
    long countByUserIdAndPairIdAndMarketTypeAndOrderStatusIn(
            UUID userId, UUID pairId, MarketType marketType, Collection<OrderStatus> statuses);

    /** How many of the account's orders of one pair on one market, of these types, are in these statuses. */
    long countByUserIdAndPairIdAndMarketTypeAndOrderStatusInAndOrderTypeIn(
            UUID userId,
            UUID pairId,
            MarketType marketType,
            Collection<OrderStatus> statuses,
            Collection<OrderType> types);

    /**
     * The accounts with an order of the pair on the market in these statuses: those a stop of the pair's trading
     * concerns.
     */
    @Query("select distinct o.userId from PaperOrder o where o.pairId = :pairId and o.marketType = :marketType"
            + " and o.orderStatus in :statuses")
    List<UUID> findUserIdsWithOrders(UUID pairId, MarketType marketType, Collection<OrderStatus> statuses);

    /** The pairs with an order on the market in these statuses: those the pair-stop sweep looks at. */
    @Query("select distinct o.pairId from PaperOrder o where o.marketType = :marketType and o.orderStatus in :statuses")
    List<UUID> findPairIdsWithOrders(MarketType marketType, Collection<OrderStatus> statuses);

    /** Writes an order. Not transactional here; the unit of work is the calling service's. */
    PaperOrder save(PaperOrder order);
}
