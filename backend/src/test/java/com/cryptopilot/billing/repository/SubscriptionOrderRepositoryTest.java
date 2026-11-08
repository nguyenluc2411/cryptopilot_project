package com.cryptopilot.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptopilot.billing.PlanFixtures;
import com.cryptopilot.billing.calculator.CarryOverCalculator;
import com.cryptopilot.billing.entity.SubscriptionOrder;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.entity.UserSubscription;
import com.cryptopilot.billing.model.PackageTerms;
import com.cryptopilot.billing.model.SubscriptionTerms;
import com.cryptopilot.billing.model.enums.GatewayCode;
import com.cryptopilot.billing.model.enums.OrderStatus;
import com.cryptopilot.billing.model.enums.OrderType;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.model.enums.SubscriptionStatus;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The order and subscription mappings, and the rules V63 puts in the schema (D-146). */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class SubscriptionOrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");

    @Autowired
    private SubscriptionOrderRepository orders;

    @Autowired
    private UserSubscriptionRepository subscriptions;

    @Autowired
    private SubscriptionPackageRepository packages;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    private Map<String, UUID> packageIds;

    @BeforeEach
    void seededPackages() {
        packageIds = PlanFixtures.seededIds(packages);
    }

    @Test
    void D146_anOrder_survivesAWriteAndAReadWithEveryColumnIntact() {
        UUID trader = trader();
        SubscriptionOrder order = newOrder(trader, "PRO_MONTHLY", OrderType.NEW);
        order.markPaid("TX-1", "00", "{\"code\": \"00\"}", NOW.plusSeconds(30));
        order.flagForManualHandling();
        orders.save(order);
        entityManager.flush();
        entityManager.clear();

        SubscriptionOrder read = orders.findByOrderCode(order.getOrderCode()).orElseThrow();

        assertThat(read.getId()).isEqualTo(order.getId());
        assertThat(orders.findById(order.getId())).isPresent();
        assertThat(read.getUserId()).isEqualTo(trader);
        assertThat(read.getPackageId()).isEqualTo(packageIds.get("PRO_MONTHLY"));
        assertThat(read.getOrderType()).isEqualTo(OrderType.NEW);
        assertThat(read.getAmount()).isEqualByComparingTo("99000");
        assertThat(read.getPaymentGateway()).isEqualTo(GatewayCode.SEPAY);
        assertThat(read.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(read.getGatewayTransactionNo()).isEqualTo("TX-1");
        assertThat(read.getGatewayPayload()).contains("\"code\"");
        assertThat(read.getPaidAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(read.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(read.isManualHandling()).isTrue();
        assertThat(jdbc.sql("select payment_gateway || '/' || order_type from subscription_order where order_id = ?")
                        .param(order.getId())
                        .query(String.class)
                        .single())
                .as("enums are stored by name")
                .isEqualTo("SEPAY/NEW");
    }

    /** The upgrade chain reads back: the old one REPLACED, the new one pointing at it with what it carried. */
    @Test
    void D146_anUpgradeChain_survivesAWriteAndARead() {
        UUID trader = trader();
        UserSubscription pro = open(trader, "PRO_MONTHLY", OrderType.NEW, null, NOW.minus(Duration.ofDays(10)));
        SubscriptionTerms upgrade = CarryOverCalculator.terms(pro.held(PlanTier.PRO), terms("PREMIUM_MONTHLY"), NOW);
        pro.replace();
        subscriptions.save(pro);
        entityManager.flush();
        UserSubscription premium = open(trader, "PREMIUM_MONTHLY", OrderType.UPGRADE, pro, NOW, upgrade);
        entityManager.flush();
        entityManager.clear();

        UserSubscription read = subscriptions
                .findByUserIdAndStatus(trader, SubscriptionStatus.ACTIVE)
                .orElseThrow();

        assertThat(read.getId()).isEqualTo(premium.getId());
        assertThat(read.getCarriedFromId()).isEqualTo(pro.getId());
        assertThat(read.getCarriedValueVnd()).isEqualByComparingTo("66000");
        assertThat(read.getBonusDays()).isEqualTo(9);
        assertThat(read.getValueVnd()).isEqualByComparingTo("265000");
        assertThat(read.getEndAt()).isEqualTo(NOW.plus(Duration.ofDays(39)));
        assertThat(subscriptions.findById(pro.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.REPLACED);
    }

    @Test
    void BR55_theDatabase_refusesASecondActiveSubscription() {
        UUID trader = trader();
        open(trader, "PRO_MONTHLY", OrderType.NEW, null, NOW);
        entityManager.flush();

        assertThatThrownBy(() -> {
                    open(trader, "PREMIUM_MONTHLY", OrderType.NEW, null, NOW);
                    entityManager.flush();
                })
                .hasStackTraceContaining("uq_user_subscription_one_active");
    }

    /** VNPAY stays legal for the rows written before SePay replaced it; anything else is refused. */
    @Test
    void D146_theGatewayColumn_takesMomoSepayAndLegacyVnpayOnly() {
        UUID trader = trader();
        for (String gateway : new String[] {"VNPAY", "MOMO", "SEPAY"}) {
            insertOrder(trader, gateway);
        }

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insertOrder(trader, "PAYPAL"))
                .withMessageContaining("ck_subscription_order_gateway");
    }

    /** A row written as SQL without the new columns takes them from its order (sample data, fixtures). */
    @Test
    void D146_aSubscriptionInsertedWithoutOwnerOrValue_takesThemFromItsOrder() {
        UUID trader = trader();
        UUID order = insertOrder(trader, "MOMO");
        UUID subscription = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(
                        subscription,
                        order,
                        Timestamp.from(NOW),
                        Timestamp.from(NOW.plus(Duration.ofDays(30))),
                        Timestamp.from(NOW),
                        Timestamp.from(NOW))
                .update();

        Map<String, Object> row = jdbc.sql("""
                        select s.user_id, s.value_vnd, s.carried_value_vnd, s.bonus_days, o.order_type
                          from user_subscription s join subscription_order o using (order_id)
                         where subscription_id = ?""").param(subscription).query().singleRow();

        assertThat(row.get("user_id")).isEqualTo(trader);
        assertThat(row.get("value_vnd").toString()).isEqualTo("99000");
        assertThat(row.get("carried_value_vnd").toString()).isEqualTo("0");
        assertThat(row.get("bonus_days")).isEqualTo(0);
        assertThat(row.get("order_type")).isEqualTo("NEW");
    }

    @Test
    void UC48_handlingColumns_areFilledOnlyOnAFlaggedOrder() {
        UUID trader = trader();
        UUID order = insertOrder(trader, "SEPAY");

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() ->
                        jdbc.sql("""
                                update subscription_order set handled_at = ?, handled_by = ?, handling_note = 'x'
                                 where order_id = ?""").params(Timestamp.from(NOW), trader, order).update())
                .withMessageContaining("ck_subscription_order_handled");
    }

    @Test
    void SCR43_eachGateway_hasASwitchThatStartsOn() {
        assertThat(jdbc.sql("""
                        select setting_key || '=' || setting_value || ':' || value_type from system_setting
                         where setting_key like 'PAYMENT_%_ENABLED' order by setting_key""").query(String.class).list())
                .containsExactly("PAYMENT_MOMO_ENABLED=true:BOOLEAN", "PAYMENT_SEPAY_ENABLED=true:BOOLEAN");
    }

    private UserSubscription open(UUID trader, String code, OrderType type, UserSubscription replaced, Instant at) {
        return open(trader, code, type, replaced, at, CarryOverCalculator.terms(null, terms(code), at));
    }

    private UserSubscription open(
            UUID trader, String code, OrderType type, UserSubscription replaced, Instant at, SubscriptionTerms terms) {
        SubscriptionOrder order = newOrder(trader, code, type);
        order.markPaid("TX-" + order.getOrderCode(), "00", null, at);
        orders.save(order);
        entityManager.flush();
        return subscriptions.save(UserSubscription.open(order, terms, replaced));
    }

    private SubscriptionOrder newOrder(UUID trader, String code, OrderType type) {
        SubscriptionPackage bought = packages.findById(packageIds.get(code)).orElseThrow();
        return SubscriptionOrder.create(
                trader,
                bought,
                type,
                GatewayCode.SEPAY,
                "CP" + UUID.randomUUID().toString().substring(0, 16),
                NOW,
                Duration.ofMinutes(15));
    }

    private PackageTerms terms(String code) {
        SubscriptionPackage p = packages.findById(packageIds.get(code)).orElseThrow();
        return new PackageTerms(p.getTier(), p.getPriceAmount(), p.getDurationDays());
    }

    private UUID insertOrder(UUID trader, String gateway) {
        UUID order = UUID.randomUUID();
        jdbc.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 99000, 'VND', ?, 'PENDING', ?, ?, ?)""")
                .params(
                        order,
                        trader,
                        packageIds.get("PRO_MONTHLY"),
                        "T066-" + order,
                        gateway,
                        Timestamp.from(NOW),
                        Timestamp.from(NOW.plusSeconds(900)),
                        Timestamp.from(NOW))
                .update();
        return order;
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""")
                .params(id, id + "@t066.invalid", Timestamp.from(NOW), Timestamp.from(NOW))
                .update();
        return id;
    }
}
