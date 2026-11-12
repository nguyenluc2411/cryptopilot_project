package com.cryptopilot.billing.service.impl;

import com.cryptopilot.admin.SystemSettingApi;
import com.cryptopilot.billing.calculator.CarryOverCalculator;
import com.cryptopilot.billing.calculator.OrderCodeGenerator;
import com.cryptopilot.billing.calculator.OrderTypeResolver;
import com.cryptopilot.billing.client.GatewayUnavailableException;
import com.cryptopilot.billing.client.PaymentGateway;
import com.cryptopilot.billing.config.BillingProperties;
import com.cryptopilot.billing.dto.request.CreateOrderRequest;
import com.cryptopilot.billing.dto.request.QuoteRequest;
import com.cryptopilot.billing.dto.response.CheckoutResponse;
import com.cryptopilot.billing.dto.response.CreateOrderResponse;
import com.cryptopilot.billing.dto.response.GatewayResponse;
import com.cryptopilot.billing.dto.response.OrderResponse;
import com.cryptopilot.billing.dto.response.PackageResponse;
import com.cryptopilot.billing.dto.response.QuoteResponse;
import com.cryptopilot.billing.dto.response.SubscriptionResponse;
import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.entity.UserSubscription;
import com.cryptopilot.billing.model.Checkout;
import com.cryptopilot.billing.model.CheckoutRequest;
import com.cryptopilot.billing.model.HeldSubscription;
import com.cryptopilot.billing.model.PackageTerms;
import com.cryptopilot.billing.model.SubscriptionTerms;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import com.cryptopilot.billing.repository.SubscriptionOrderRepository;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.repository.UserSubscriptionRepository;
import com.cryptopilot.billing.service.BillingService;
import com.cryptopilot.billing.service.OrderReconciliationService;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.common.lock.UserLock;
import com.cryptopilot.common.web.PageResponse;
import com.cryptopilot.common.web.Paging;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The Trader side of subscriptions and payment (SCR-30…32, SCR-07).
 *
 * <p>An order is created under the Trader's billing lock, so two tabs cannot both price an upgrade from the same
 * subscription. Its type is decided from the ACTIVE subscription held: a lower tier is refused (D-147). Its amount is
 * the package price now (BR-56). The gateway must take new orders (not the legacy VNPAY), be switched on by an
 * administrator and be configured on this deployment; switching it off later does not touch orders already created.
 * The gateway is asked for its payment page inside the transaction, so an order it refuses is not kept.
 *
 * <p>Rule: BR-53…BR-56, BR-63; SRS 3.10; TECHNICAL_DESIGN 7.11.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Transactional(readOnly = true)
public class BillingServiceImpl implements BillingService {

    /** The scope of the Trader's billing lock (D-63), apart from the watchlist and plan writes. */
    static final String LOCK_SCOPE = "billing";

    /**
     * The orders the payment history shows: those a payment was actually made for, paid or failed. An order opened
     * and never paid (PENDING, EXPIRED, withdrawn) is left out; the result page still reads it by its code.
     */
    static final Set<OrderStatus> HISTORY = EnumSet.of(OrderStatus.PAID, OrderStatus.FAILED);

    /** BR-54: minutes an order stays payable. */
    static final String ORDER_EXPIRY_MINUTES = "ORDER_EXPIRY_MINUTES";

    /** The administrator's switch of each gateway that takes new orders (D-147). */
    static final Map<GatewayCode, String> SWITCHES =
            Map.of(GatewayCode.MOMO, "PAYMENT_MOMO_ENABLED", GatewayCode.SEPAY, "PAYMENT_SEPAY_ENABLED");

    /** Draws before giving up on a free order code; one collision is already a one-in-1.7-million event. */
    static final int CODE_ATTEMPTS = 5;

    private static final RandomGenerator RANDOM = new SecureRandom();

    private final SubscriptionPackageRepository packages;
    private final SubscriptionOrderRepository orders;
    private final UserSubscriptionRepository subscriptions;
    private final ObjectProvider<PaymentGateway> gateways;
    private final SystemSettingApi settings;
    private final UserLock userLock;
    private final BillingProperties properties;
    private final OrderReconciliationService reconciliation;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    public List<PackageResponse> packages() {
        return packages.findAllByActiveTrueOrderByTierRankAsc().stream()
                .filter(p -> p.getTier() == PlanTier.FREE || p.isPurchasable())
                .map(BillingServiceImpl::toResponse)
                .toList();
    }

    @Override
    public List<GatewayResponse> gateways() {
        return Arrays.stream(GatewayCode.values())
                .filter(GatewayCode::acceptsNewOrders)
                .map(code -> new GatewayResponse(code, open(code)))
                .toList();
    }

    @Override
    public QuoteResponse quote(UUID userId, QuoteRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        SubscriptionPackage bought = onSale(request.packageCode());
        Instant now = clock.instant();
        Optional<HeldPlan> held = held(userId);
        requireNotADowngrade(held, bought, now);
        SubscriptionTerms terms =
                CarryOverCalculator.terms(held.map(HeldPlan::subscription).orElse(null), termsOf(bought), now);
        return new QuoteResponse(
                bought.getPackageCode(),
                terms.orderType(),
                bought.getPriceAmount(),
                bought.getCurrency(),
                terms.carriedValueVnd(),
                terms.bonusDays(),
                terms.startAt(),
                terms.endAt());
    }

    /**
     * Creates the Trader's order. The Trader's unpaid order, if any, is first released at its gateway (outside any
     * transaction, since the gateway is called): a Trader holds one PENDING order at a time, so a page or QR code left
     * open can never pay a second plan (D-147).
     */
    @Override
    // SUPPORTS: no transaction of its own (the class default is read-only), so the gateway is called outside one.
    @Transactional(propagation = Propagation.SUPPORTS)
    public CreateOrderResponse createOrder(UUID userId, CreateOrderRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        try {
            reconciliation.releasePending(userId);
        } catch (GatewayUnavailableException e) {
            throw new BusinessException(
                    ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    "the Trader's unpaid order could not be released: " + e.getMessage(),
                    e);
        }
        return transactions.execute(status -> open(userId, request));
    }

    private CreateOrderResponse open(UUID userId, CreateOrderRequest request) {
        userLock.lock(LOCK_SCOPE, userId);
        Optional<SubscriptionOrder> pending = orders.findByUserIdAndOrderStatus(userId, OrderStatus.PENDING).stream()
                .findFirst();
        if (pending.isPresent()) {
            throw new BusinessException(
                    ErrorCode.ORDER_ALREADY_PENDING,
                    "order " + pending.get().getOrderCode() + " still awaits payment",
                    pending.get().getOrderCode());
        }
        SubscriptionPackage bought = onSale(request.packageCode());
        PaymentGateway gateway = openGateway(request.gateway());
        Instant now = clock.instant();
        OrderType type = requireNotADowngrade(held(userId), bought, now);
        Duration expiry = Duration.ofMinutes(settings.integer(ORDER_EXPIRY_MINUTES));
        SubscriptionOrder order =
                orders.save(SubscriptionOrder.create(userId, bought, type, gateway.code(), freeCode(now), now, expiry));
        Checkout checkout;
        try {
            checkout = gateway.checkout(new CheckoutRequest(
                    order.getOrderCode(),
                    order.getAmount(),
                    "CryptoPilot " + bought.getPackageCode() + " " + order.getOrderCode(),
                    UriComponentsBuilder.fromUri(properties.returnUrl())
                            .queryParam("orderCode", order.getOrderCode())
                            .build()
                            .toUri(),
                    order.getExpiresAt()));
        } catch (GatewayUnavailableException e) {
            throw new BusinessException(
                    ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    gateway.code() + " did not open a payment page: " + e.getMessage(),
                    e);
        }
        return new CreateOrderResponse(
                toResponse(order, bought),
                new CheckoutResponse(checkout.method(), checkout.url().toString(), checkout.fields()));
    }

    @Override
    public PageResponse<OrderResponse> orders(UUID userId, Integer page, Integer pageSize) {
        Objects.requireNonNull(userId, "userId must not be null");
        Map<UUID, SubscriptionPackage> seen = new HashMap<>();
        return PageResponse.of(
                orders.findByUserIdAndOrderStatusInOrderByCreatedAtDescIdDesc(
                        userId, HISTORY, Paging.of(page, pageSize)),
                order -> toResponse(order, seen.computeIfAbsent(order.getPackageId(), this::packageOf)));
    }

    @Override
    public OrderResponse order(UUID userId, String orderCode) {
        Objects.requireNonNull(userId, "userId must not be null");
        SubscriptionOrder order = orders.findByUserIdAndOrderCode(userId, orderCode)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "no order " + orderCode + " of the caller"));
        return toResponse(order, packageOf(order.getPackageId()));
    }

    @Override
    public SubscriptionResponse subscription(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Instant now = clock.instant();
        return held(userId)
                .filter(plan -> !plan.row().getStartAt().isAfter(now)
                        && plan.row().getEndAt().isAfter(now))
                .map(plan -> new SubscriptionResponse(
                        plan.bought().getTier(),
                        plan.bought().getPackageCode(),
                        plan.bought().getPackageName(),
                        plan.row().getStartAt(),
                        plan.row().getEndAt(),
                        plan.row().getCarriedValueVnd(),
                        plan.row().getBonusDays()))
                .orElseGet(() -> new SubscriptionResponse(PlanTier.FREE, null, null, null, null, null, null));
    }

    /** The order type, or MSG43 with the plan held and its end when the package is of a lower tier. */
    private static OrderType requireNotADowngrade(Optional<HeldPlan> held, SubscriptionPackage bought, Instant now) {
        HeldSubscription subscription = held.map(HeldPlan::subscription).orElse(null);
        return OrderTypeResolver.resolve(subscription, bought.getTier(), now)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SUBSCRIPTION_DOWNGRADE_REFUSED,
                        "the caller holds " + held.orElseThrow().bought().getPackageCode()
                                + " and may only renew it or upgrade",
                        held.orElseThrow().bought().getPackageName(),
                        held.orElseThrow().row().getEndAt()));
    }

    /** The package with this code if it is a paid package on sale; FREE is never sold. */
    private SubscriptionPackage onSale(String code) {
        SubscriptionPackage found = packages.findByPackageCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "no package " + code));
        if (found.getTier() == PlanTier.FREE || !found.isPurchasable() || !found.isActive()) {
            throw new BusinessException(ErrorCode.PACKAGE_NOT_ON_SALE, "package " + code + " is not on sale");
        }
        return found;
    }

    private PaymentGateway openGateway(GatewayCode code) {
        if (!open(code)) {
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_DISABLED, code + " takes no new orders now");
        }
        return implementation(code).orElseThrow();
    }

    /** Whether a new order may be paid through the gateway now: switched on and configured here. */
    private boolean open(GatewayCode code) {
        return code.acceptsNewOrders()
                && settings.flag(SWITCHES.get(code))
                && implementation(code).filter(PaymentGateway::configured).isPresent();
    }

    private Optional<PaymentGateway> implementation(GatewayCode code) {
        return gateways.orderedStream().filter(g -> g.code() == code).findFirst();
    }

    private String freeCode(Instant now) {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = OrderCodeGenerator.next(now, RANDOM);
            if (!orders.existsByOrderCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("no free order code after " + CODE_ATTEMPTS + " draws");
    }

    /** The Trader's ACTIVE subscription with the package of the order that created it, or empty. */
    private Optional<HeldPlan> held(UUID userId) {
        return subscriptions
                .findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .map(row -> {
                    SubscriptionOrder order = orders.findById(row.getOrderId()).orElseThrow();
                    return new HeldPlan(row, packageOf(order.getPackageId()));
                });
    }

    private SubscriptionPackage packageOf(UUID packageId) {
        return packages.findById(packageId).orElseThrow();
    }

    private static PackageTerms termsOf(SubscriptionPackage bought) {
        return new PackageTerms(bought.getTier(), bought.getPriceAmount(), bought.getDurationDays());
    }

    private static OrderResponse toResponse(SubscriptionOrder order, SubscriptionPackage bought) {
        return new OrderResponse(
                order.getOrderCode(),
                bought.getPackageCode(),
                bought.getPackageName(),
                order.getOrderType(),
                order.getAmount(),
                order.getCurrency(),
                order.getPaymentGateway(),
                order.getOrderStatus(),
                order.getCreatedAt(),
                order.getExpiresAt(),
                order.getPaidAt());
    }

    private static PackageResponse toResponse(SubscriptionPackage p) {
        var e = p.entitlements();
        return new PackageResponse(
                p.getPackageCode(),
                p.getPackageName(),
                p.getTier(),
                p.getPriceAmount(),
                p.getCurrency(),
                p.getDurationDays(),
                p.isPurchasable(),
                e.futuresAnalysis(),
                e.scoreComponents(),
                e.activePlanMax(),
                e.watchlistMax(),
                e.activeAlertMax(),
                e.indicatorAlert(),
                e.externalAlertChannels(),
                e.advancedPerformance(),
                e.newsAiInsight(),
                e.aiChatDaily(),
                e.aiPerformanceContext(),
                e.videoPost());
    }

    /** The ACTIVE subscription and the package its order bought. */
    private record HeldPlan(UserSubscription row, SubscriptionPackage bought) {

        HeldSubscription subscription() {
            return row.held(bought.getTier());
        }
    }
}
