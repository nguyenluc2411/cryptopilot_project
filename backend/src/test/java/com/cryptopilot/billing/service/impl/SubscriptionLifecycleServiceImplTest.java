package com.cryptopilot.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.event.PlanDowngraded;
import com.cryptopilot.billing.event.SubscriptionEndingSoon;
import com.cryptopilot.billing.service.SubscriptionLifecycleService;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * The hourly upkeep against the database (NSF-14, BR-64): ended subscriptions expire and announce the drop to FREE,
 * and one reminder is announced per subscription ending within 3 days. Other tests' rows may be handled by the same
 * run, so only this test's Traders are asserted.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
@RecordApplicationEvents
class SubscriptionLifecycleServiceImplTest {

    private static final Map<String, UUID> PACKAGES = Map.of(
            "PRO_MONTHLY", UUID.fromString("019b76da-a800-7002-8000-000000000002"),
            "PREMIUM_MONTHLY", UUID.fromString("019b76da-a800-7002-8000-000000000004"));

    @Autowired
    private SubscriptionLifecycleService lifecycle;

    @Autowired
    private JdbcClient sql;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationEvents events;

    @Test
    void NSF14_anEndedSubscription_expires_andAnnouncesTheDropToFree() {
        UUID trader = trader();
        UUID ended =
                subscribe(trader, "PREMIUM_MONTHLY", Instant.now().minus(Duration.ofDays(31)), Duration.ofDays(30));
        UUID other = trader();
        UUID running = subscribe(other, "PRO_MONTHLY", Instant.now().minus(Duration.ofDays(1)), Duration.ofDays(30));

        assertThat(lifecycle.expireEnded()).isPositive();
        entityManager.flush();

        assertThat(status(ended)).isEqualTo("EXPIRED");
        assertThat(status(running)).isEqualTo("ACTIVE");
        String free = sql.sql("select package_name from subscription_package where tier = 'FREE'")
                .query(String.class)
                .single();
        assertThat(events.stream(PlanDowngraded.class).filter(e -> e.userId().equals(trader)))
                .singleElement()
                .satisfies(drop -> {
                    assertThat(drop.fromTier()).isEqualTo("PREMIUM");
                    assertThat(drop.toTier()).isEqualTo("FREE");
                    assertThat(drop.toPlanName()).isEqualTo(free);
                });
        assertThat(events.stream(PlanDowngraded.class).filter(e -> e.userId().equals(other)))
                .isEmpty();

        lifecycle.expireEnded();
        assertThat(events.stream(PlanDowngraded.class).filter(e -> e.userId().equals(trader)))
                .hasSize(1);
    }

    @Test
    void NSF14_aSubscriptionEndingWithin3Days_isRemindedOnce() {
        UUID trader = trader();
        UUID soon = subscribe(trader, "PRO_MONTHLY", Instant.now().minus(Duration.ofDays(28)), Duration.ofDays(30));
        UUID later = trader();
        subscribe(later, "PRO_MONTHLY", Instant.now().minus(Duration.ofDays(20)), Duration.ofDays(30));

        assertThat(lifecycle.remindEnding()).isPositive();
        entityManager.flush();
        lifecycle.remindEnding();
        entityManager.flush();

        assertThat(events.stream(SubscriptionEndingSoon.class)
                        .filter(e -> e.userId().equals(trader)))
                .singleElement()
                .satisfies(reminder -> {
                    assertThat(reminder.subscriptionId()).isEqualTo(soon);
                    assertThat(reminder.packageName()).isNotBlank();
                });
        assertThat(events.stream(SubscriptionEndingSoon.class)
                        .filter(e -> e.userId().equals(later)))
                .isEmpty();
        assertThat(sql.sql("select reminded_at from user_subscription where subscription_id = ?")
                        .param(soon)
                        .query(Timestamp.class)
                        .single())
                .isNotNull();
    }

    private String status(UUID subscription) {
        return sql.sql("select subscription_status from user_subscription where subscription_id = ?")
                .param(subscription)
                .query(String.class)
                .single();
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
                        "NSF14-" + order,
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

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        sql.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""").params(id, id + "@nsf14.invalid", now, now).update();
        return id;
    }
}
