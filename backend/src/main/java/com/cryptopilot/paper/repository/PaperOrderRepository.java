package com.cryptopilot.paper.repository;

import com.cryptopilot.paper.entity.PaperOrder;
import com.cryptopilot.paper.model.RestingOrder;
import com.cryptopilot.paper.model.enums.OrderStatus;
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
    Optional<PaperOrder> findByIdAndAccountId(UUID orderId, UUID accountId);

    /** The account's order with this idempotency key, or empty. */
    @Transactional(readOnly = true)
    Optional<PaperOrder> findByAccountIdAndClientOrderId(UUID accountId, String clientOrderId);

    /** The account's orders in these statuses, newest first. */
    @Transactional(readOnly = true)
    List<PaperOrder> findByAccountIdAndOrderStatusInOrderByCreatedAtDescIdDesc(
            UUID accountId, Collection<OrderStatus> statuses);

    /**
     * One page of an account's orders in these statuses, placed in {@code [from, to)}, newest first. No parameter is
     * null, so PostgreSQL can type every one.
     */
    @Transactional(readOnly = true)
    @Query(
            value = "select o from PaperOrder o where o.accountId = :accountId and o.orderStatus in :statuses"
                    + " and o.createdAt >= :from and o.createdAt < :to order by o.createdAt desc, o.id desc",
            countQuery = "select count(o) from PaperOrder o where o.accountId = :accountId"
                    + " and o.orderStatus in :statuses and o.createdAt >= :from and o.createdAt < :to")
    Page<PaperOrder> search(
            UUID accountId, Collection<OrderStatus> statuses, Instant from, Instant to, Pageable pageable);

    /** As {@link #search}, for one pair. */
    @Transactional(readOnly = true)
    @Query(
            value = "select o from PaperOrder o where o.accountId = :accountId and o.pairId = :pairId"
                    + " and o.orderStatus in :statuses and o.createdAt >= :from and o.createdAt < :to"
                    + " order by o.createdAt desc, o.id desc",
            countQuery = "select count(o) from PaperOrder o where o.accountId = :accountId and o.pairId = :pairId"
                    + " and o.orderStatus in :statuses and o.createdAt >= :from and o.createdAt < :to")
    Page<PaperOrder> searchPair(
            UUID accountId, UUID pairId, Collection<OrderStatus> statuses, Instant from, Instant to, Pageable pageable);

    /** Every working LIMIT order of every account, which the matching engine starts its books from. */
    @Transactional(readOnly = true)
    @Query("select new com.cryptopilot.paper.model.RestingOrder(o.id, o.accountId, o.marketType, o.pairId, o.side,"
            + " o.limitPrice, o.createdAt) from PaperOrder o"
            + " where o.orderStatus in :statuses and o.orderType = com.cryptopilot.paper.model.enums.OrderType.LIMIT")
    List<RestingOrder> findResting(Collection<OrderStatus> statuses);

    /** Writes an order. Not transactional here; the unit of work is the calling service's. */
    PaperOrder save(PaperOrder order);
}
