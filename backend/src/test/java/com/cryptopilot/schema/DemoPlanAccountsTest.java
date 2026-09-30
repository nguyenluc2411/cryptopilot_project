package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.model.enums.Feature;
import com.cryptopilot.billing.model.enums.PlanTier;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.billing.service.impl.EntitlementServiceImpl;
import com.cryptopilot.support.TestcontainersConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileCopyUtils;

/**
 * The demo Traders of each plan tier, read through the entitlement checks. The demo file is applied inside the test's
 * transaction on top of the production migrations, so the checks read it exactly as they read a real subscription, and
 * the rollback leaves the shared database without a demo row (which {@link SeedDataTest} asserts).
 *
 * <p>The checks run on a fixed clock: the subscriptions have fixed instants, so the plan is asserted at the capstone
 * demonstration and at the end of the subscriptions rather than at whatever day the suite runs.
 *
 * <p>Rule: BR-62, BR-54, BR-56; SRS v1.1 UC-53; D-62.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class DemoPlanAccountsTest {

    private static final String DEMO_FILE = "db/demo/R__demo_plan_accounts.sql";

    /** A day of the capstone demonstration, inside the demo subscriptions. */
    private static final Instant DEMO_DAY = Instant.parse("2027-03-15T03:00:00Z");

    /** The end of the demo subscriptions, the first instant they no longer cover. */
    private static final Instant SUBSCRIPTIONS_END = Instant.parse("2027-09-28T00:00:00Z");

    private static final Map<String, UUID> ACCOUNTS = Map.of(
            "trader.free@cryptopilot.invalid", UUID.fromString("019b76da-a800-7d01-8000-000000000001"),
            "trader.pro@cryptopilot.invalid", UUID.fromString("019b76da-a800-7d01-8000-000000000002"),
            "trader.premium@cryptopilot.invalid", UUID.fromString("019b76da-a800-7d01-8000-000000000003"));

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private SubscriptionPackageRepository packages;

    @BeforeEach
    void applyTheDemoFile() throws Exception {
        String sql = new String(
                FileCopyUtils.copyToByteArray(new ClassPathResource(DEMO_FILE).getInputStream()),
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> placeholder :
                flyway.getConfiguration().getPlaceholders().entrySet()) {
            sql = sql.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        // The connection of the test's transaction, so the rows roll back with it.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), DEMO_FILE),
                            StandardCharsets.UTF_8));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Test
    void theDemoTraders_areVerifiedTradersWhosePasswordComesFromTheEnvironment() {
        List<Map<String, Object>> rows = jdbc.sql("""
                        select email, role, account_status, email_verified_at, password_hash
                          from user_account where email like 'trader.%@cryptopilot.invalid'""").query().listOfRows();

        assertThat(rows).extracting(row -> row.get("email")).containsExactlyInAnyOrderElementsOf(ACCOUNTS.keySet());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("role")).isEqualTo("TRADER");
            assertThat(row.get("account_status")).isEqualTo("ACTIVE");
            assertThat(row.get("email_verified_at")).isNotNull();
            assertThat(row.get("password_hash"))
                    .as("the hash is a placeholder filled from the environment, never a literal in the file")
                    .isEqualTo(flyway.getConfiguration().getPlaceholders().get("demo_password_hash"));
        });
        assertThat(jdbc.sql("select count(*) from user_profile where user_id in (:ids)")
                        .param("ids", ACCOUNTS.values())
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
    }

    /** UC-53: on the day of the demonstration each account is on its own tier. */
    @ParameterizedTest(name = "{0} is on {1}")
    @CsvSource({
        "trader.free@cryptopilot.invalid,    FREE,    false, 5",
        "trader.pro@cryptopilot.invalid,     PRO,     true,  50",
        "trader.premium@cryptopilot.invalid, PREMIUM, true,  100"
    })
    void BR62_eachDemoTrader_isOnItsTier(String email, PlanTier tier, boolean futures, int watchlistMax) {
        EntitlementApi entitlements = entitlementsAt(DEMO_DAY);
        UUID trader = ACCOUNTS.get(email);

        assertThat(entitlements.effectivePlan(trader).tier()).isEqualTo(tier);
        assertThat(entitlements.hasFeature(trader, Feature.FUTURES_ANALYSIS)).isEqualTo(futures);
        assertThat(entitlements.getLimit(trader, Feature.WATCHLIST_MAX)).isEqualTo(watchlistMax);
    }

    /** The subscriptions run 365 days: at their end the paid accounts are back on FREE. */
    @Test
    void BR62_atTheEndOfTheDemoSubscriptions_everyDemoTraderIsOnFree() {
        EntitlementApi entitlements = entitlementsAt(SUBSCRIPTIONS_END);

        assertThat(ACCOUNTS.values())
                .allSatisfy(trader ->
                        assertThat(entitlements.effectivePlan(trader).tier()).isEqualTo(PlanTier.FREE));
    }

    /** BR-56, BR-54: each order is PAID, copies its yearly package's price and had the 15-minute payment window. */
    @Test
    void BR56_eachDemoOrder_copiesItsYearlyPackagePrice_andIsPaid() {
        List<Map<String, Object>> orders =
                jdbc.sql("""
                        select p.package_code, o.amount = p.price_amount as copies_price, o.order_status,
                               o.expires_at - o.created_at = interval '15 minutes' as payment_window,
                               s.subscription_status, s.end_at - s.start_at = interval '365 days' as one_year
                          from subscription_order o
                          join subscription_package p on p.package_id = o.package_id
                          join user_subscription s on s.order_id = o.order_id
                         where o.user_id in (:ids)
                         order by p.package_code""").param("ids", ACCOUNTS.values()).query().listOfRows();

        assertThat(orders).extracting(row -> row.get("package_code")).containsExactly("PREMIUM_YEARLY", "PRO_YEARLY");
        assertThat(orders).allSatisfy(row -> {
            assertThat(row.get("copies_price")).isEqualTo(true);
            assertThat(row.get("order_status")).isEqualTo("PAID");
            assertThat(row.get("payment_window")).isEqualTo(true);
            assertThat(row.get("subscription_status")).isEqualTo("ACTIVE");
            assertThat(row.get("one_year")).isEqualTo(true);
        });
    }

    private EntitlementApi entitlementsAt(Instant now) {
        return new EntitlementServiceImpl(packages, Clock.fixed(now, ZoneOffset.UTC));
    }
}
