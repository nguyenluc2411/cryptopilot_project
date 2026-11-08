package com.cryptopilot.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.event.SubscriptionOrderPaid;
import com.cryptopilot.billing.model.PaymentResult;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.service.PaymentSettlementService;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement and activation against the database (BR-53, NSF-12, A-03, D-146). The clock is the system clock, so
 * periods are asserted within a minute of now.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
@RecordApplicationEvents
class PaymentSettlementServiceImplTest {

    private static final Map<String, UUID> PACKAGES = Map.of(
            "PRO_MONTHLY", UUID.fromString("019b76da-a800-7002-8000-000000000002"),
            "PRO_YEARLY", UUID.fromString("019b76da-a800-7002-8000-000000000003"),
            "PREMIUM_MONTHLY", UUID.fromString("019b76da-a800-7002-8000-000000000004"));

    @Autowired
    private PaymentSettlementService settlement;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationEvents events;

    @Test
    void NSF12_aConfirmedPayment_paysTheOrderAndOpensTheSubscription() {
        UUID trader = trader();
        String order = pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now());

        settlement.settle(paid(order, "99000.00"));
        entityManager.flush();

        Map<String, Object> row = orderRow(order);
        assertThat(row.get("order_status")).isEqualTo("PAID");
        assertThat(row.get("gateway_transaction_no")).isEqualTo("TX-" + order);
        assertThat(row.get("gateway_response_code")).isEqualTo("CAPTURED");
        assertThat(row.get("paid_at")).isNotNull();
        List<Map<String, Object>> subs = subscriptions(trader);
        assertThat(subs).hasSize(1);
        assertThat(subs.getFirst().get("subscription_status")).isEqualTo("ACTIVE");
        assertThat(subs.getFirst().get("value_vnd").toString()).isEqualTo("99000");
        assertThat(days(subs.getFirst())).isEqualTo(30);
        assertThat(events.stream(SubscriptionOrderPaid.class)).singleElement().satisfies(paid -> {
            assertThat(paid.orderCode()).isEqualTo(order);
            assertThat(paid.userId()).isEqualTo(trader);
            assertThat(paid.orderType()).isEqualTo("NEW");
            assertThat(paid.amount()).isEqualByComparingTo("99000");
            assertThat(paid.gateway()).isEqualTo("SEPAY");
            assertThat(paid.transactionNo()).isEqualTo("TX-" + order);
            assertThat(paid.bonusDays()).isZero();
        });
    }

    /** BR-53: the same IPN twice opens one subscription. */
    @Test
    void BR53_aRepeatedReport_changesNothingTheSecondTime() {
        UUID trader = trader();
        String order = pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now());

        settlement.settle(paid(order, "99000"));
        entityManager.flush();
        settlement.settle(paid(order, "99000"));
        settlement.settle(new PaymentResult(GatewayCode.SEPAY, order, false, null, null, "CANCELLED", "{}"));
        entityManager.flush();

        assertThat(subscriptions(trader)).hasSize(1);
        assertThat(events.stream(SubscriptionOrderPaid.class)).hasSize(1);
        assertThat(orderRow(order).get("order_status")).isEqualTo("PAID");
    }

    /** BR-53: another amount activates nothing. */
    @Test
    void BR53_aPaymentOfAnotherAmount_failsTheOrderAndActivatesNothing() {
        UUID trader = trader();
        String order = pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now());

        settlement.settle(paid(order, "1000"));
        entityManager.flush();

        assertThat(orderRow(order).get("order_status")).isEqualTo("FAILED");
        assertThat(orderRow(order).get("gateway_response_code")).isEqualTo("AMOUNT_MISMATCH");
        assertThat(subscriptions(trader)).isEmpty();
    }

    @Test
    void NSF12_aReportedFailure_failsThePendingOrder() {
        UUID trader = trader();
        String order = pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now());

        settlement.settle(new PaymentResult(GatewayCode.SEPAY, order, false, null, null, "CANCELLED", "{}"));
        entityManager.flush();

        assertThat(orderRow(order).get("order_status")).isEqualTo("FAILED");
        assertThat(subscriptions(trader)).isEmpty();
    }

    /** A-03: the Trader was charged, so a payment after the order expired still activates it. */
    @Test
    void A03_aPaymentAfterTheOrderExpired_isHonoured() {
        UUID trader = trader();
        String order =
                pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now().minus(Duration.ofHours(1)));
        sql.sql("update subscription_order set order_status = 'EXPIRED' where order_code = ?")
                .param(order)
                .update();

        settlement.settle(paid(order, "99000"));
        entityManager.flush();

        assertThat(orderRow(order).get("order_status")).isEqualTo("PAID");
        assertThat(subscriptions(trader)).hasSize(1);
    }

    /** An expired order that the gateway reports as failed stays expired. */
    @Test
    void BR54_aFailureReportedForAnExpiredOrder_leavesItExpired() {
        UUID trader = trader();
        String order =
                pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now().minus(Duration.ofHours(1)));
        sql.sql("update subscription_order set order_status = 'EXPIRED' where order_code = ?")
                .param(order)
                .update();

        settlement.settle(new PaymentResult(GatewayCode.SEPAY, order, false, null, null, "CANCELLED", "{}"));
        settlement.settle(paid(order, "5"));
        entityManager.flush();

        assertThat(orderRow(order).get("order_status")).isEqualTo("EXPIRED");
    }

    /** D-146: PRO_MONTHLY with 20 of 30 days left, upgraded to PREMIUM_MONTHLY: +9 days, the old one REPLACED. */
    @Test
    void D146_anUpgrade_replacesTheSubscriptionAndAddsTheBonusDays() {
        UUID trader = trader();
        UUID pro = subscribe(trader, "PRO_MONTHLY", Instant.now().minus(Duration.ofDays(10)), Duration.ofDays(30));
        String order = pendingOrder(trader, "PREMIUM_MONTHLY", "SEPAY", Instant.now());

        settlement.settle(paid(order, "199000"));
        entityManager.flush();

        assertThat(status(pro)).isEqualTo("REPLACED");
        Map<String, Object> premium = active(trader);
        assertThat(premium.get("carried_from_id")).isEqualTo(pro);
        assertThat(premium.get("bonus_days")).isEqualTo(9);
        assertThat(days(premium)).isEqualTo(39);
        assertThat(new BigDecimal(premium.get("carried_value_vnd").toString()))
                .isBetween(new BigDecimal("65990"), new BigDecimal("66000"));
    }

    /** D-146: a renewal adds the new period after the current end. */
    @Test
    void D146_aRenewal_addsThePeriodAfterTheCurrentEnd() {
        UUID trader = trader();
        Instant start = Instant.now().minus(Duration.ofDays(10));
        UUID pro = subscribe(trader, "PRO_MONTHLY", start, Duration.ofDays(30));
        String order = pendingOrder(trader, "PRO_YEARLY", "SEPAY", Instant.now());

        settlement.settle(paid(order, "990000"));
        entityManager.flush();

        assertThat(status(pro)).isEqualTo("REPLACED");
        Map<String, Object> renewed = active(trader);
        Instant end = ((Timestamp) renewed.get("end_at")).toInstant();
        assertThat(Duration.between(start.plus(Duration.ofDays(30 + 365)), end).abs())
                .isLessThan(Duration.ofSeconds(1));
        assertThat(renewed.get("bonus_days")).isEqualTo(0);
    }

    /** The order was created on PRO; the Trader then reached PREMIUM through another order: flag, change nothing. */
    @Test
    void D146_anOrderThatBecameADowngrade_isPaidFlaggedAndChangesNoSubscription() {
        UUID trader = trader();
        String order = pendingOrder(trader, "PRO_YEARLY", "SEPAY", Instant.now());
        UUID premium =
                subscribe(trader, "PREMIUM_MONTHLY", Instant.now().minus(Duration.ofDays(1)), Duration.ofDays(30));

        settlement.settle(paid(order, "990000"));
        entityManager.flush();

        assertThat(orderRow(order).get("order_status")).isEqualTo("PAID");
        assertThat(orderRow(order).get("manual_handling")).isEqualTo(true);
        assertThat(status(premium)).isEqualTo("ACTIVE");
        assertThat(subscriptions(trader)).hasSize(1);
        assertThat(events.stream(SubscriptionOrderPaid.class)).isEmpty();
    }

    /** An ACTIVE row past its end (the expiry job has not run) is expired, and the purchase is a new period. */
    @Test
    void NSF14_aSubscriptionPastItsEnd_isExpiredBeforeTheNewOne() {
        UUID trader = trader();
        UUID ended =
                subscribe(trader, "PREMIUM_MONTHLY", Instant.now().minus(Duration.ofDays(31)), Duration.ofDays(30));
        String order = pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now());

        settlement.settle(paid(order, "99000"));
        entityManager.flush();

        assertThat(status(ended)).isEqualTo("EXPIRED");
        assertThat(active(trader).get("carried_from_id")).isNull();
    }

    @Test
    void BR53_aReportAboutAnUnknownOrderOrFromAnotherGateway_changesNothing() {
        UUID trader = trader();
        String order = pendingOrder(trader, "PRO_MONTHLY", "MOMO", Instant.now());

        settlement.settle(paid("CP000000000000NONE", "99000"));
        settlement.settle(paid(order, "99000"));
        entityManager.flush();

        assertThat(orderRow(order).get("order_status")).isEqualTo("PENDING");
        assertThat(subscriptions(trader)).isEmpty();
    }

    /** BR-54: an unpaid order expires once its window has closed, and only then. */
    @Test
    void BR54_anUnpaidOrder_expiresWhenItsWindowHasClosed() {
        UUID trader = trader();
        String due = pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now().minus(Duration.ofMinutes(20)));
        String open = pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now());

        assertThat(settlement.expireIfDue(due)).isTrue();
        assertThat(settlement.expireIfDue(open)).isFalse();
        entityManager.flush();

        assertThat(orderRow(due).get("order_status")).isEqualTo("EXPIRED");
        assertThat(orderRow(open).get("order_status")).isEqualTo("PENDING");
        assertThat(settlement.expireIfDue(due)).isFalse();
        assertThat(settlement.expireIfDue("CP-NO-SUCH-ORDER")).isFalse();
    }

    /** BR-54: a settled order never expires. */
    @Test
    void BR54_aPaidOrder_doesNotExpire() {
        UUID trader = trader();
        String order =
                pendingOrder(trader, "PRO_MONTHLY", "SEPAY", Instant.now().minus(Duration.ofMinutes(20)));
        settlement.settle(paid(order, "99000"));
        entityManager.flush();

        assertThat(settlement.expireIfDue(order)).isFalse();
        assertThat(orderRow(order).get("order_status")).isEqualTo("PAID");
    }

    private static PaymentResult paid(String order, String amount) {
        return new PaymentResult(
                GatewayCode.SEPAY, order, true, new BigDecimal(amount), "TX-" + order, "CAPTURED", "{\"ok\":true}");
    }

    private String pendingOrder(UUID trader, String packageCode, String gateway, Instant createdAt) {
        String code = "CP"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        Timestamp at = Timestamp.from(createdAt);
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, expires_at,
                                                        updated_at)
                        select ?, ?, package_id, ?, price_amount, 'VND', ?, 'PENDING', ?, ?, ?
                          from subscription_package where package_id = ?""")
                .params(
                        UUID.randomUUID(),
                        trader,
                        code,
                        gateway,
                        at,
                        Timestamp.from(createdAt.plusSeconds(900)),
                        at,
                        PACKAGES.get(packageCode))
                .update();
        return code;
    }

    private UUID subscribe(UUID trader, String packageCode, Instant start, Duration period) {
        UUID order = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        Timestamp at = Timestamp.from(start);
        sql.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        select ?, ?, package_id, ?, price_amount, 'VND', 'SEPAY', 'PAID', ?, ?, ?, ?
                          from subscription_package where package_id = ?""")
                .params(
                        order,
                        trader,
                        "T069-" + order,
                        at,
                        at,
                        Timestamp.from(start.plusSeconds(900)),
                        at,
                        PACKAGES.get(packageCode))
                .update();
        sql.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(subscription, order, at, Timestamp.from(start.plus(period)), at, at)
                .update();
        return subscription;
    }

    private Map<String, Object> orderRow(String code) {
        return sql.sql("select * from subscription_order where order_code = ?")
                .param(code)
                .query()
                .singleRow();
    }

    private List<Map<String, Object>> subscriptions(UUID trader) {
        return sql.sql("select * from user_subscription where user_id = ?")
                .param(trader)
                .query()
                .listOfRows();
    }

    private Map<String, Object> active(UUID trader) {
        return sql.sql("select * from user_subscription where user_id = ? and subscription_status = 'ACTIVE'")
                .param(trader)
                .query()
                .singleRow();
    }

    private String status(UUID subscription) {
        return sql.sql("select subscription_status from user_subscription where subscription_id = ?")
                .param(subscription)
                .query(String.class)
                .single();
    }

    private static long days(Map<String, Object> subscription) {
        Instant start = ((Timestamp) subscription.get("start_at")).toInstant();
        Instant end = ((Timestamp) subscription.get("end_at")).toInstant();
        return Duration.between(start, end).plusMinutes(1).toDays();
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@t069.invalid", now, now).update();
        return id;
    }
}
