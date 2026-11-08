package com.cryptopilot.billing.service.impl;

import com.cryptopilot.admin.AdminApi;
import com.cryptopilot.admin.AdminMetricSource;
import com.cryptopilot.admin.AuditEntry;
import com.cryptopilot.admin.model.enums.AuditAction;
import com.cryptopilot.admin.model.enums.AuditedEntity;
import com.cryptopilot.billing.dto.AdminOrderDetailResponse;
import com.cryptopilot.billing.dto.AdminOrderResponse;
import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.repository.UserSubscriptionRepository;
import com.cryptopilot.billing.service.OrderAdminService;
import com.cryptopilot.billing.service.OrderReconciliationService;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.exception.FieldValidationException;
import com.cryptopilot.common.exception.ResourceNotFoundException;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The administrator's payment console (SCR-40, UC-48). Reconciling asks the gateway outside any transaction, as the
 * scheduled run does, and audits the result in a transaction of its own; marking an order handled locks it first, so
 * two administrators cannot both handle it (BR-57).
 *
 * <p>The list names the Trader by id only: the {@code user} module depends on this one, so this module does not read
 * accounts. The console opens the account from the id.
 *
 * <p>Rule: UC-48; NSF-13; BR-57; D-146.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class OrderAdminServiceImpl implements OrderAdminService {

    private static final ZoneId ZONE = ZoneId.of(AdminMetricSource.ZONE);

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final SubscriptionOrderRepository orders;
    private final SubscriptionPackageRepository packages;
    private final UserSubscriptionRepository subscriptions;
    private final OrderReconciliationService reconciliation;
    private final AdminApi admin;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderResponse> list(Criteria criteria, Integer page, Integer pageSize) {
        if (criteria.from() != null && criteria.to() != null && criteria.from().isAfter(criteria.to())) {
            throw new FieldValidationException("the period starts after it ends", Map.of("from", "MSG15"));
        }
        Map<UUID, SubscriptionPackage> byId =
                packages.findAll().stream().collect(Collectors.toMap(SubscriptionPackage::getId, Function.identity()));
        return PageResponse.of(
                orders.findAll(matching(criteria), Paging.of(page, pageSize, NEWEST_FIRST)),
                order -> row(order, byId.get(order.getPackageId())));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminOrderDetailResponse detail(String orderCode) {
        return detail(order(orderCode));
    }

    @Override
    public AdminOrderDetailResponse reconcile(UUID adminId, String orderCode) {
        OrderStatus before = order(orderCode).getOrderStatus();
        OrderStatus after = reconciliation.reconcile(orderCode);
        return transactions.execute(status -> {
            SubscriptionOrder order = order(orderCode);
            admin.audit(new AuditEntry(
                    adminId,
                    AuditAction.ORDER_RECONCILED,
                    AuditedEntity.SUBSCRIPTION_ORDER,
                    order.getId(),
                    Map.of("orderCode", orderCode, "status", before.name()),
                    Map.of("orderCode", orderCode, "status", after.name())));
            return detail(order);
        });
    }

    @Override
    @Transactional
    public AdminOrderDetailResponse markHandled(UUID adminId, String orderCode, String note) {
        SubscriptionOrder order = orders.lockByOrderCode(orderCode)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription order", orderCode));
        try {
            order.markHandled(adminId, note.strip(), clock.instant());
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.ORDER_NOT_AWAITING_HANDLING, e.getMessage());
        }
        Map<String, Object> handled = new LinkedHashMap<>();
        handled.put("orderCode", orderCode);
        handled.put("handled", true);
        handled.put("note", order.getHandlingNote());
        admin.audit(new AuditEntry(
                adminId,
                AuditAction.ORDER_HANDLED,
                AuditedEntity.SUBSCRIPTION_ORDER,
                order.getId(),
                Map.of("orderCode", orderCode, "handled", false),
                handled));
        return detail(order);
    }

    private SubscriptionOrder order(String orderCode) {
        return orders.findByOrderCode(orderCode)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription order", orderCode));
    }

    private AdminOrderDetailResponse detail(SubscriptionOrder order) {
        SubscriptionPackage bought = packages.findById(order.getPackageId()).orElseThrow();
        AdminOrderDetailResponse.Subscription opened = subscriptions
                .findByOrderId(order.getId())
                .map(s -> new AdminOrderDetailResponse.Subscription(
                        s.getStatus(), s.getStartAt(), s.getEndAt(), s.getCarriedValueVnd(), s.getBonusDays()))
                .orElse(null);
        return new AdminOrderDetailResponse(
                row(order, bought),
                order.getGatewayTransactionNo(),
                order.getGatewayResponseCode(),
                order.getGatewayPayload(),
                order.getIpnReceivedAt(),
                order.getHandledBy(),
                order.getHandlingNote(),
                opened);
    }

    private static AdminOrderResponse row(SubscriptionOrder order, SubscriptionPackage bought) {
        return new AdminOrderResponse(
                order.getOrderCode(),
                order.getUserId(),
                bought == null ? null : bought.getPackageCode(),
                bought == null ? null : bought.getPackageName(),
                bought == null ? null : bought.getTier(),
                order.getOrderType(),
                order.getAmount(),
                order.getCurrency(),
                order.getPaymentGateway(),
                order.getOrderStatus(),
                order.getCreatedAt(),
                order.getExpiresAt(),
                order.getPaidAt(),
                order.isManualHandling(),
                order.getHandledAt());
    }

    private static Specification<SubscriptionOrder> matching(Criteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (criteria.status() != null) {
                where.add(cb.equal(root.get("orderStatus"), criteria.status()));
            }
            if (criteria.orderType() != null) {
                where.add(cb.equal(root.get("orderType"), criteria.orderType()));
            }
            if (criteria.gateway() != null) {
                where.add(cb.equal(root.get("paymentGateway"), criteria.gateway()));
            }
            if (criteria.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOf(criteria.from())));
            }
            if (criteria.to() != null) {
                where.add(
                        cb.lessThan(root.get("createdAt"), startOf(criteria.to().plusDays(1))));
            }
            if (criteria.manual() != null) {
                where.add(cb.equal(root.get("manualHandling"), criteria.manual()));
            }
            if (criteria.orderCode() != null && !criteria.orderCode().isBlank()) {
                String prefix = criteria.orderCode()
                        .strip()
                        .toUpperCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                where.add(cb.like(root.get("orderCode"), prefix + "%", '\\'));
            }
            if (criteria.userId() != null) {
                where.add(cb.equal(root.get("userId"), criteria.userId()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }
}
