package com.cryptopilot.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cryptopilot.support.TestcontainersConfig;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The plan tier columns V10 adds to {@code subscription_package}: each constraint is broken on its own and has to be
 * the one that refuses the row. PostgreSQL tests the check constraints of a row in the alphabetical order of their
 * names, so the constraint named in the failure is determined, and removing a constraint makes its test fail
 * instead of passing on a neighbour.
 *
 * <p>Every test rolls back.
 *
 * <p>Rule: BR-62; SRS v1.1 entity 34, SRS 3.11.5; D-58, D-59.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class PlanTierMigrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-27T00:00:00Z");

    @Autowired
    private JdbcClient jdbc;

    // ------------------------------------------------------------------ accepted

    /** One FREE package and paid packages of both tiers; the maxima may be null (unlimited). */
    @Test
    void BR62_aFreePackage_andPaidPackagesWithUnlimitedMaxima_areAccepted() {
        assertThatCode(() -> {
                    insert(free());
                    insert(paid("PRO_MONTHLY", "PRO", 1));
                    Map<String, Object> premium = paid("PREMIUM_YEARLY", "PREMIUM", 2);
                    premium.put("duration_days", 365);
                    insert(premium);
                })
                .doesNotThrowAnyException();
        assertThat(jdbc.sql("select count(*) from subscription_package where active_plan_max is null")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    /** A paid package may be priced 0 while it has a duration: the schema does not decide promotions. */
    @Test
    void BR62_aPaidPackageAtZeroWithADuration_isAccepted() {
        Map<String, Object> row = paid("PRO_TRIAL", "PRO", 1);
        row.put("price_amount", BigDecimal.ZERO);
        row.put("is_purchasable", false);

        assertThatCode(() -> insert(row)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ FREE

    @Test
    void BR62_aSecondFreePackage_isRefused() {
        insert(free());
        Map<String, Object> second = free();
        second.put("package_code", "FREE_2");

        refused(second, "uq_subscription_package_one_free");
    }

    /** FREE is priced 0, has no duration and cannot be bought; any one of the three broken is refused. */
    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({"price_amount, 99000", "duration_days, 30", "is_purchasable, true"})
    void BR62_aFreePackageThatLooksPaid_isRefused(String column, String value) {
        Map<String, Object> row = free();
        row.put(
                column,
                switch (column) {
                    case "price_amount" -> new BigDecimal(value);
                    case "duration_days" -> Integer.valueOf(value);
                    default -> Boolean.valueOf(value);
                });

        refused(row, "ck_subscription_package_free");
    }

    // ------------------------------------------------------------------ paid tiers

    /** A paid package priced 0, without duration and not on sale is what FREE looks like: refused. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"PRO, 1", "PREMIUM, 2"})
    void BR62_aPaidPackageThatLooksFree_isRefused(String tier, int rank) {
        Map<String, Object> row = paid("LOOKS_FREE", tier, rank);
        row.put("price_amount", BigDecimal.ZERO);
        row.put("duration_days", null);
        row.put("is_purchasable", false);

        refused(row, "ck_subscription_package_free");
    }

    /** A paid package always has a duration, whatever its price (SRS 3.11.5). */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"PRO, 1", "PREMIUM, 2"})
    void BR62_aPaidPackageWithoutADuration_isRefused(String tier, int rank) {
        Map<String, Object> row = paid("NO_DURATION", tier, rank);
        row.put("duration_days", null);

        refused(row, "ck_subscription_package_paid_duration");
    }

    @ParameterizedTest(name = "{0} days")
    @ValueSource(ints = {0, 367, -30})
    void BR62_aDurationOutsideOneTo366Days_isRefused(int days) {
        Map<String, Object> row = paid("BAD_DURATION", "PRO", 1);
        row.put("duration_days", days);

        refused(row, "ck_subscription_package_duration");
    }

    @ParameterizedTest(name = "{0} days")
    @ValueSource(ints = {1, 366})
    void BR62_theDurationBounds_areAccepted(int days) {
        Map<String, Object> row = paid("EDGE_DURATION", "PRO", 1);
        row.put("duration_days", days);

        assertThatCode(() -> insert(row)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ tier and rank

    @Test
    void BR62_aTierOutsideTheThree_isRefused() {
        refused(paid("GOLD", "GOLD", 1), "ck_subscription_package_tier");
    }

    @ParameterizedTest(name = "{0} ranked {1}")
    @CsvSource({"FREE, 1", "PRO, 0", "PRO, 2", "PREMIUM, 1"})
    void BR62_aRankThatIsNotTheTiers_isRefused(String tier, int rank) {
        Map<String, Object> row = "FREE".equals(tier) ? free() : paid("BAD_RANK", tier, rank);
        row.put("tier_rank", rank);

        refused(row, "ck_subscription_package_tier_rank");
    }

    // ------------------------------------------------------------------ amounts

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"active_plan_max", "watchlist_max", "active_alert_max", "ai_daily_quota"})
    void BR62_aNegativeLimitOrQuota_isRefused(String column) {
        Map<String, Object> row = paid("NEGATIVE", "PRO", 1);
        row.put(column, -1);

        refused(row, "ck_subscription_package_" + column);
    }

    @Test
    void BR62_aZeroLimit_isAccepted() {
        Map<String, Object> row = paid("ZERO_LIMITS", "PRO", 1);
        row.put("watchlist_max", 0);
        row.put("active_alert_max", 0);
        row.put("active_plan_max", 0);
        row.put("ai_daily_quota", 0);

        assertThatCode(() -> insert(row)).doesNotThrowAnyException();
    }

    @Test
    void BR56_aNegativePrice_isRefused() {
        Map<String, Object> row = paid("NEGATIVE_PRICE", "PRO", 1);
        row.put("price_amount", new BigDecimal("-1"));

        refused(row, "ck_subscription_package_price");
    }

    // ------------------------------------------------------------------ fixtures

    /** The FREE package of Table 3.1. */
    private static Map<String, Object> free() {
        Map<String, Object> row = base("FREE", "FREE", 0);
        row.put("price_amount", BigDecimal.ZERO);
        row.put("duration_days", null);
        row.put("is_purchasable", false);
        row.put("active_plan_max", 3);
        row.put("watchlist_max", 5);
        row.put("active_alert_max", 3);
        row.put("ai_daily_quota", 0);
        return row;
    }

    /** A paid package on sale for 30 days, unlimited ACTIVE plans. */
    private static Map<String, Object> paid(String code, String tier, int rank) {
        Map<String, Object> row = base(code, tier, rank);
        row.put("price_amount", new BigDecimal("99000"));
        row.put("duration_days", 30);
        row.put("is_purchasable", true);
        row.put("active_plan_max", null);
        row.put("watchlist_max", 50);
        row.put("active_alert_max", 20);
        row.put("ai_daily_quota", 30);
        return row;
    }

    private static Map<String, Object> base(String code, String tier, int rank) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("package_id", UUID.randomUUID());
        row.put("package_code", code);
        row.put("package_name", code);
        row.put("currency", "VND");
        row.put("tier", tier);
        row.put("tier_rank", rank);
        for (String flag : new String[] {
            "futures_analysis",
            "score_components",
            "indicator_alert",
            "external_alert_channels",
            "advanced_performance",
            "news_ai_insight",
            "ai_performance_context",
            "can_post_video"
        }) {
            row.put(flag, !"FREE".equals(tier));
        }
        row.put("created_at", NOW);
        row.put("updated_at", NOW);
        return row;
    }

    private void insert(Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String marks = String.join(", ", row.keySet().stream().map(c -> "?").toList());
        jdbc.sql("insert into subscription_package (" + columns + ") values (" + marks + ")")
                .params(row.values().toArray())
                .update();
    }

    /** Quoted as PostgreSQL quotes it, so {@code …_tier} is not satisfied by {@code …_tier_rank}. */
    private void refused(Map<String, Object> row, String constraint) {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(row))
                .withStackTraceContaining("\"" + constraint + "\"");
    }
}
