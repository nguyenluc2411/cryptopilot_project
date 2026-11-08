package com.cryptopilot.billing.repository;

import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.model.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscription orders of every Trader.
 *
 * <p>Rule: BR-53, BR-54; SRS v1.1 entity 35; TECHNICAL_DESIGN 7.11; D-146.
 */
public interface SubscriptionOrderRepository
        extends Repository<SubscriptionOrder, UUID>, JpaSpecificationExecutor<SubscriptionOrder> {

    SubscriptionOrder save(SubscriptionOrder order);

    @Transactional(readOnly = true)
    Optional<SubscriptionOrder> findById(UUID orderId);

    /** The order a gateway or a return URL names, or empty. */
    @Transactional(readOnly = true)
    Optional<SubscriptionOrder> findByOrderCode(String orderCode);

    /** The Trader's order with this code, or empty when there is none or it is another Trader's. */
    @Transactional(readOnly = true)
    Optional<SubscriptionOrder> findByUserIdAndOrderCode(UUID userId, String orderCode);

    /** The Trader's orders in this status: at most one PENDING (uq_subscription_order_one_pending). */
    @Transactional(readOnly = true)
    List<SubscriptionOrder> findByUserIdAndOrderStatus(UUID userId, OrderStatus status);

    /** Whether the Trader has an order in this status. */
    @Transactional(readOnly = true)
    boolean existsByUserIdAndOrderStatus(UUID userId, OrderStatus status);

    /** Whether an order already has this code. */
    @Transactional(readOnly = true)
    boolean existsByOrderCode(String orderCode);

    /** The Trader's orders, newest first (SCR-32). */
    @Transactional(readOnly = true)
    Page<SubscriptionOrder> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable page);

    /**
     * The order with this code, its row locked until the transaction ends, so two reports of the same payment are
     * settled one after the other and the second finds it settled (BR-53).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SubscriptionOrder o where o.orderCode = :orderCode")
    Optional<SubscriptionOrder> lockByOrderCode(@Param("orderCode") String orderCode);

    /**
     * The codes of the orders in this status created before {@code before}, oldest first, one page of them: the
     * orders a reconciliation run asks the gateway about (NSF-13), by the {@code (order_status, created_at)} index.
     */
    @Transactional(readOnly = true)
    @Query("""
            select o.orderCode from SubscriptionOrder o
             where o.orderStatus = :status and o.createdAt < :before
             order by o.createdAt, o.id""")
    List<String> findCodesCreatedBefore(
            @Param("status") OrderStatus status, @Param("before") Instant before, Pageable page);
}
