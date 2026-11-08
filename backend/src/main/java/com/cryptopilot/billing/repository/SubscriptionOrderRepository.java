package com.cryptopilot.billing.repository;

import com.cryptopilot.billing.entity.SubscriptionOrder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscription orders of every Trader.
 *
 * <p>Rule: BR-53, BR-54; SRS v1.1 entity 35; TECHNICAL_DESIGN 7.11; D-146.
 */
public interface SubscriptionOrderRepository extends Repository<SubscriptionOrder, UUID> {

    SubscriptionOrder save(SubscriptionOrder order);

    @Transactional(readOnly = true)
    Optional<SubscriptionOrder> findById(UUID orderId);

    /** The order a gateway or a return URL names, or empty. */
    @Transactional(readOnly = true)
    Optional<SubscriptionOrder> findByOrderCode(String orderCode);

    /** The Trader's order with this code, or empty when there is none or it is another Trader's. */
    @Transactional(readOnly = true)
    Optional<SubscriptionOrder> findByUserIdAndOrderCode(UUID userId, String orderCode);

    /** Whether an order already has this code. */
    @Transactional(readOnly = true)
    boolean existsByOrderCode(String orderCode);

    /** The Trader's orders, newest first (SCR-32). */
    @Transactional(readOnly = true)
    Page<SubscriptionOrder> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable page);
}
