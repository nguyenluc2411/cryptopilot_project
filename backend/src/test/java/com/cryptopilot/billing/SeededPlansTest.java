package com.cryptopilot.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.support.TestcontainersConfig;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entitlement checks against a freshly migrated database and nothing else: no test stores a package, so every
 * answer comes from the packages the production seed wrote. This is what a new environment gives a Trader on its
 * first request.
 *
 * <p>Every test rolls back.
 *
 * <p>Rule: BR-62; SRS v1.1 UC-53, Table 3.1; D-58, D-62.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class SeededPlansTest {

    @Autowired
    private EntitlementApi entitlements;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    /** D-62: a Trader without a subscription is on the seeded FREE plan, with no fixture in place. */
    @Test
    void BR62_aTraderWithoutASubscription_isOnTheSeededFreePlan() {
        UUID trader = trader();

        EffectivePlan plan = entitlements.effectivePlan(trader);

        assertThat(plan.tier()).isEqualTo(PlanTier.FREE);
        assertThat(entitlements.hasFeature(trader, Feature.FUTURES_ANALYSIS)).isFalse();
        assertThat(entitlements.getLimit(trader, Feature.WATCHLIST_MAX)).isEqualTo(5);
        assertThat(entitlements.getLimit(trader, Feature.ACTIVE_PLAN_MAX)).isEqualTo(3);
    }

    /** A running subscription to a seeded paid package gives that package's tier and its limits. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "019b76da-a800-7002-8000-000000000003, PRO,     50,  30",
        "019b76da-a800-7002-8000-000000000005, PREMIUM, 100, 100"
    })
    void BR62_aSubscriptionToASeededPackage_givesItsTier(
            UUID packageId, PlanTier tier, int watchlistMax, int aiDailyQuota) {
        UUID trader = trader();
        subscribe(trader, packageId);

        assertThat(entitlements.effectivePlan(trader).tier()).isEqualTo(tier);
        assertThat(entitlements.hasFeature(trader, Feature.FUTURES_ANALYSIS)).isTrue();
        assertThat(entitlements.getLimit(trader, Feature.WATCHLIST_MAX)).isEqualTo(watchlistMax);
        assertThat(entitlements.getLimit(trader, Feature.AI_CHAT_DAILY)).isEqualTo(aiDailyQuota);
        assertThat(entitlements.getLimit(trader, Feature.ACTIVE_PLAN_MAX))
                .as("unlimited")
                .isNull();
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""")
                .params(id, id + "@t007.invalid", Timestamp.from(now), Timestamp.from(now))
                .update();
        return id;
    }

    /** A PAID order for the package and an ACTIVE subscription that started a day ago and runs for a year. */
    private void subscribe(UUID trader, UUID packageId) {
        Instant start = clock.instant().minus(Duration.ofDays(1));
        UUID order = UUID.randomUUID();
        jdbc.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 1, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        trader,
                        packageId,
                        "T007-" + order,
                        Timestamp.from(start),
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(900)),
                        Timestamp.from(start))
                .update();
        jdbc.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(start),
                        Timestamp.from(start.plus(Duration.ofDays(365))),
                        Timestamp.from(start),
                        Timestamp.from(start))
                .update();
    }
}
