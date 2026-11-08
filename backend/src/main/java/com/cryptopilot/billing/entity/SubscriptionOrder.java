package com.cryptopilot.billing.entity;

import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A Trader's order for a paid package, paid through one gateway and settled once (BR-53).
 *
 * <p>The amount is the package's list price when the order is created and never changes afterwards (BR-56); there is
 * no credit, so the amount is also the list price. An order is created PENDING and leaves it once: PAID, FAILED or
 * EXPIRED. A success reported after the order expired is still honoured, since the Trader was charged (A-03). An order
 * paid after the Trader's subscription changed under it, so that it would now be a downgrade, stays PAID, changes no
 * subscription and is flagged for an administrator, who records how it was handled (SCR-40, UC-48).
 *
 * <p>Rule: BR-53, BR-54, BR-56; SRS v1.1 entity 35; TECHNICAL_DESIGN 7.11; A-03; D-58, D-146.
 */
@Getter
@Entity
@Table(name = "subscription_order")
@AttributeOverride(name = "id", column = @Column(name = "order_id", nullable = false, updatable = false))
public class SubscriptionOrder extends BaseEntity {

    /** The only currency an order is charged in. */
    static final String CURRENCY = "VND";

    /** Longest note an administrator writes when handling a flagged order ({@code varchar(1000)}). */
    static final int HANDLING_NOTE_LENGTH = 1000;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;

    @Column(name = "order_code", nullable = false, length = 64, updatable = false)
    private String orderCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 32, updatable = false)
    private OrderType orderType;

    /** The list price of the package when the order was created, in VND (BR-56). */
    @Column(name = "amount", nullable = false, precision = 18, scale = 0, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 32, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_gateway", nullable = false, length = 32, updatable = false)
    private GatewayCode paymentGateway;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 32)
    private OrderStatus orderStatus;

    @Column(name = "gateway_transaction_no", length = 100)
    private String gatewayTransactionNo;

    @Column(name = "gateway_response_code", length = 32)
    private String gatewayResponseCode;

    /** What the gateway last answered, as JSON, for the administrator (SCR-40). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gateway_payload")
    private String gatewayPayload;

    @Column(name = "ipn_received_at")
    private Instant ipnReceivedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "manual_handling", nullable = false)
    private boolean manualHandling;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "handling_note", length = HANDLING_NOTE_LENGTH)
    private String handlingNote;

    /** For JPA only. */
    protected SubscriptionOrder() {}

    private SubscriptionOrder(
            UUID userId,
            SubscriptionPackage bought,
            OrderType orderType,
            GatewayCode gateway,
            String orderCode,
            Instant now,
            Duration expiry) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.packageId = bought.getId();
        this.orderType = Objects.requireNonNull(orderType, "orderType must not be null");
        this.paymentGateway = gateway;
        this.orderCode = requireCode(orderCode);
        this.amount = bought.getPriceAmount();
        this.currency = CURRENCY;
        this.orderStatus = OrderStatus.PENDING;
        this.expiresAt = now.plus(expiry);
    }

    /**
     * A PENDING order for {@code bought}, charged its current price and payable until {@code now + expiry} (BR-54).
     *
     * @param bought a paid package on sale
     * @param gateway a gateway that takes new orders (not the legacy VNPAY)
     * @param expiry {@code ORDER_EXPIRY_MINUTES}, above zero
     */
    public static SubscriptionOrder create(
            UUID userId,
            SubscriptionPackage bought,
            OrderType orderType,
            GatewayCode gateway,
            String orderCode,
            Instant now,
            Duration expiry) {
        Objects.requireNonNull(bought, "bought must not be null");
        Objects.requireNonNull(gateway, "gateway must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(expiry, "expiry must not be null");
        if (!bought.isPurchasable() || !bought.isActive()) {
            throw new IllegalArgumentException("package " + bought.getPackageCode() + " is not on sale");
        }
        if (!gateway.acceptsNewOrders()) {
            throw new IllegalArgumentException(gateway + " takes no new orders");
        }
        if (expiry.isNegative() || expiry.isZero()) {
            throw new IllegalArgumentException("an order stays payable for some time, was " + expiry);
        }
        return new SubscriptionOrder(userId, bought, orderType, gateway, orderCode, now, expiry);
    }

    /**
     * Records the gateway's confirmation of the full amount. A PENDING order becomes PAID; so does an EXPIRED one,
     * because the Trader was charged (A-03).
     *
     * @throws IllegalStateException when the order is already PAID or FAILED (BR-53: settled once)
     */
    public void markPaid(String transactionNo, String responseCode, String payload, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (orderStatus != OrderStatus.PENDING && orderStatus != OrderStatus.EXPIRED) {
            throw new IllegalStateException("order " + orderCode + " is " + orderStatus + ", it cannot be paid");
        }
        recordAnswer(responseCode, payload, now);
        this.gatewayTransactionNo = transactionNo;
        this.orderStatus = OrderStatus.PAID;
        this.paidAt = now;
    }

    /**
     * Records the gateway's report that the payment failed or was cancelled.
     *
     * @throws IllegalStateException when the order is no longer PENDING
     */
    public void markFailed(String responseCode, String payload, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requirePending("fail");
        recordAnswer(responseCode, payload, now);
        this.orderStatus = OrderStatus.FAILED;
    }

    /**
     * Ends an order nobody paid in time (BR-54).
     *
     * @throws IllegalStateException when the order is no longer PENDING or is still payable at {@code now}
     */
    public void expire(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        requirePending("expire");
        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException("order " + orderCode + " is payable until " + expiresAt);
        }
        this.orderStatus = OrderStatus.EXPIRED;
    }

    /**
     * Flags a paid order whose subscription change no longer applies, for an administrator to handle (SCR-40).
     *
     * @throws IllegalStateException when the order is not PAID
     */
    public void flagForManualHandling() {
        if (orderStatus != OrderStatus.PAID) {
            throw new IllegalStateException("only a PAID order is flagged, " + orderCode + " is " + orderStatus);
        }
        this.manualHandling = true;
    }

    /**
     * Records that an administrator handled the flagged order, and how (UC-48).
     *
     * @throws IllegalStateException when the order is not flagged or was handled already
     */
    public void markHandled(UUID administratorId, String note, Instant now) {
        Objects.requireNonNull(administratorId, "administratorId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!manualHandling || handledAt != null) {
            throw new IllegalStateException("order " + orderCode + " is not waiting for an administrator");
        }
        if (note != null && note.length() > HANDLING_NOTE_LENGTH) {
            throw new IllegalArgumentException("a handling note is at most " + HANDLING_NOTE_LENGTH + " characters");
        }
        this.handledBy = administratorId;
        this.handledAt = now;
        this.handlingNote = note;
    }

    private void recordAnswer(String responseCode, String payload, Instant now) {
        this.gatewayResponseCode = responseCode;
        this.gatewayPayload = payload;
        this.ipnReceivedAt = now;
    }

    private void requirePending(String action) {
        if (orderStatus != OrderStatus.PENDING) {
            throw new IllegalStateException("order " + orderCode + " is " + orderStatus + ", it cannot " + action);
        }
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("an order code is 1–64 characters, was " + code);
        }
        return code;
    }
}
