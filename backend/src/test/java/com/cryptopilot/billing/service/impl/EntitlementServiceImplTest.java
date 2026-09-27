package com.cryptopilot.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cryptopilot.billing.EntitlementApi;
import com.cryptopilot.billing.Feature;
import com.cryptopilot.billing.PlanFixtures;
import com.cryptopilot.billing.PlanTier;
import com.cryptopilot.billing.entity.SubscriptionPackage;
import com.cryptopilot.billing.repository.SubscriptionPackageRepository;
import com.cryptopilot.common.exception.BusinessException;
import com.cryptopilot.common.exception.ErrorCode;
import com.cryptopilot.support.TestcontainersConfig;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entitlement checks of UC-53 against the migrated schema: which package is the effective plan at a given instant,
 * what each of the twelve codes of D-58 answers on each tier, and how a refusal carries MSG29, MSG27 or MSG30. The
 * service is built with a fixed clock, so the instants of each subscription are placed exactly around "now".
 *
 * <p>Every test rolls back.
 *
 * <p>Rule: BR-62, BR-50, BR-15, BR-17; SRS v1.1 UC-53, SRS 3.10.4; D-58, D-59, D-61.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class EntitlementServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-27T05:00:00Z");

    @Autowired
    private SubscriptionPackageRepository packages;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntitlementApi entitlementApi;

    private EntitlementServiceImpl service;
    private Map<String, UUID> packageIds;

    @BeforeEach
    void setUp() {
        service = new EntitlementServiceImpl(packages, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Other modules reach the checks through the module's public interface, which this service implements. */
    @Test
    void UC53_theChecks_arePublishedAsTheBillingModulesApi() {
        assertThat(entitlementApi).isInstanceOf(EntitlementServiceImpl.class);
    }

    // ------------------------------------------------------------------ the effective plan

    @Test
    void BR62_aTraderWithoutASubscription_isOnFree() {
        storePlans();

        assertThat(service.effectivePlan(trader()).tier()).isEqualTo(PlanTier.FREE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PRO_MONTHLY", "PRO_YEARLY", "PREMIUM_MONTHLY", "PREMIUM_YEARLY"})
    void BR62_anActiveSubscriptionRunningNow_givesItsPackagesTier(String packageCode) {
        storePlans();
        UUID trader = trader();
        subscribe(trader, packageCode, "ACTIVE", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(29)));

        assertThat(service.effectivePlan(trader).tier())
                .isEqualTo(PlanTier.valueOf(packageCode.split("_")[0]));
    }

    /** SRS 3.10.4: an ACTIVE row past its end is FREE at once, whether or not the expiry job has run. */
    @Test
    void BR62_anActiveSubscriptionPastItsEnd_isFree_beforeTheExpiryJobRuns() {
        storePlans();
        UUID trader = trader();
        subscribe(trader, "PRO_MONTHLY", "ACTIVE", NOW.minus(Duration.ofDays(31)), NOW.minusSeconds(1));

        assertThat(service.effectivePlan(trader).tier()).isEqualTo(PlanTier.FREE);
    }

    @Test
    void BR62_aSubscriptionThatStartsLater_isNotHeldYet() {
        storePlans();
        UUID trader = trader();
        subscribe(trader, "PREMIUM_MONTHLY", "ACTIVE", NOW.plusSeconds(1), NOW.plus(Duration.ofDays(30)));

        assertThat(service.effectivePlan(trader).tier()).isEqualTo(PlanTier.FREE);
    }

    /** The period is {@code [start_at, end_at)}: held at its first instant, not at its end. */
    @Test
    void BR62_theSubscriptionPeriod_includesItsStartAndExcludesItsEnd() {
        storePlans();
        UUID startsNow = trader();
        UUID endsNow = trader();
        subscribe(startsNow, "PRO_MONTHLY", "ACTIVE", NOW, NOW.plus(Duration.ofDays(30)));
        subscribe(endsNow, "PRO_MONTHLY", "ACTIVE", NOW.minus(Duration.ofDays(30)), NOW);

        assertThat(service.effectivePlan(startsNow).tier()).isEqualTo(PlanTier.PRO);
        assertThat(service.effectivePlan(endsNow).tier()).isEqualTo(PlanTier.FREE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"EXPIRED", "CANCELLED"})
    void BR62_aSubscriptionThatIsNotActive_isNotHeldEvenInsideItsPeriod(String status) {
        storePlans();
        UUID trader = trader();
        subscribe(trader, "PREMIUM_YEARLY", status, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364)));

        assertThat(service.effectivePlan(trader).tier()).isEqualTo(PlanTier.FREE);
    }

    /** Two ACTIVE subscriptions at once should not exist; when they do, the higher tier applies. */
    @Test
    void BR62_twoOverlappingActiveSubscriptions_giveTheHigherTier() {
        storePlans();
        UUID trader = trader();
        subscribe(trader, "PRO_YEARLY", "ACTIVE", NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(355)));
        subscribe(trader, "PREMIUM_MONTHLY", "ACTIVE", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(29)));

        assertThat(service.effectivePlan(trader).tier()).isEqualTo(PlanTier.PREMIUM);
    }

    /** Another Trader's subscription is not mine. */
    @Test
    void BR62_anotherTradersSubscription_isNotHeld() {
        storePlans();
        UUID mine = trader();
        subscribe(trader(), "PREMIUM_MONTHLY", "ACTIVE", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(29)));

        assertThat(service.effectivePlan(mine).tier()).isEqualTo(PlanTier.FREE);
    }

    @Test
    void BR62_withoutAStoredFreePackage_theServiceFailsLoudly() {
        assertThatIllegalStateException()
                .isThrownBy(() -> service.effectivePlan(trader()))
                .withMessageContaining("FREE");
    }

    // ------------------------------------------------------------------ the twelve codes on each tier

    /** Table 3.1 and D-58: switches as 0/1, limits as numbers, {@code -} for unlimited. */
    @ParameterizedTest(name = "{0}: FREE {1}, PRO {2}, PREMIUM {3}")
    @CsvSource({
        "FUTURES_ANALYSIS, 0, 1, 1",
        "SCORE_COMPONENTS, 0, 1, 1",
        "ACTIVE_PLAN_MAX, 3, -, -",
        "WATCHLIST_MAX, 5, 50, 100",
        "ACTIVE_ALERT_MAX, 3, 20, 50",
        "INDICATOR_ALERT, 0, 1, 1",
        "EXTERNAL_ALERT_CHANNELS, 0, 1, 1",
        "ADVANCED_PERFORMANCE, 0, 1, 1",
        "NEWS_AI_INSIGHT, 0, 1, 1",
        "AI_CHAT_DAILY, 0, 30, 100",
        "AI_PERFORMANCE_CONTEXT, 0, 0, 1",
        "VIDEO_POST, 0, 0, 1",
    })
    void BR62_eachCode_answersTheValueOfTable3_1_onEachTier(String code, String free, String pro, String premium) {
        storePlans();
        Feature feature = Feature.valueOf(code);
        Map<PlanTier, UUID> traders = tradersOnEachTier();

        Map<PlanTier, String> expected = Map.of(PlanTier.FREE, free, PlanTier.PRO, pro, PlanTier.PREMIUM, premium);
        expected.forEach((tier, value) -> {
            UUID trader = traders.get(tier);
            if (feature.kind() == Feature.Kind.SWITCH) {
                assertThat(service.hasFeature(trader, feature))
                        .as("%s on %s", feature, tier)
                        .isEqualTo("1".equals(value));
            } else {
                Integer limit = "-".equals(value) ? null : Integer.valueOf(value);
                assertThat(service.getLimit(trader, feature))
                        .as("%s on %s", feature, tier)
                        .isEqualTo(limit);
                assertThat(service.hasFeature(trader, feature))
                        .as("%s included on %s", feature, tier)
                        .isEqualTo(limit == null || limit > 0);
            }
        });
    }

    // ------------------------------------------------------------------ requireFeature (MSG29)

    @Test
    void CR08_aFeatureOutsideThePlan_isRefusedWithMsg29_namingTheLowestTierThatHasIt() {
        storePlans();
        Map<PlanTier, UUID> traders = tradersOnEachTier();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.requireFeature(traders.get(PlanTier.FREE), Feature.FUTURES_ANALYSIS))
                .satisfies(e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.PLAN_FEATURE_NOT_INCLUDED);
                    assertThat(e.messageArgs()).containsExactly("PRO");
                });
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.requireFeature(traders.get(PlanTier.PRO), Feature.VIDEO_POST))
                .satisfies(e -> assertThat(e.messageArgs()).containsExactly("PREMIUM"));
        assertThatCode(() -> service.requireFeature(traders.get(PlanTier.PREMIUM), Feature.VIDEO_POST))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.requireFeature(traders.get(PlanTier.PRO), Feature.ACTIVE_PLAN_MAX))
                .as("an unlimited maximum is included")
                .doesNotThrowAnyException();
    }

    /** A tier whose packages are all withdrawn cannot be bought, so MSG29 names the next tier that is on offer. */
    @Test
    void CR08_msg29_skipsATierWhosePackagesAreAllWithdrawn() {
        storePlans();
        jdbc.sql("update subscription_package set is_active = false where tier = 'PRO'")
                .update();
        entityManager.clear();
        UUID free = trader();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.requireFeature(free, Feature.FUTURES_ANALYSIS))
                .satisfies(e -> assertThat(e.messageArgs()).containsExactly("PREMIUM"));
    }

    @Test
    void CR08_aFeatureNoActivePackageIncludes_isAConfigurationError() {
        packages.save(SubscriptionPackage.free("FREE", "Free", PlanFixtures.FREE));
        entityManager.flush();

        assertThatIllegalStateException()
                .isThrownBy(() -> service.requireFeature(trader(), Feature.VIDEO_POST))
                .withMessageContaining("VIDEO_POST");
    }

    // ------------------------------------------------------------------ requireWithinLimit (MSG27)

    /** BR-15: the watchlist limit of each tier — one below passes, at the maximum the next add is refused. */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({"FREE, 5", "PRO, 50", "PREMIUM, 100"})
    void BR15_theWatchlistLimitOfEachTier_refusesTheItemAfterTheMaximum(PlanTier tier, int max) {
        assertLimit(tier, Feature.WATCHLIST_MAX, max, "watchlist items");
    }

    /** BR-17: the active alert limit of each tier, the same way. */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({"FREE, 3", "PRO, 20", "PREMIUM, 50"})
    void BR17_theActiveAlertLimitOfEachTier_refusesTheAlertAfterTheMaximum(PlanTier tier, int max) {
        assertLimit(tier, Feature.ACTIVE_ALERT_MAX, max, "active alerts");
    }

    @Test
    void BR62_theActivePlanLimit_isThreeOnFree_andNeverReachedWhereUnlimited() {
        storePlans();
        Map<PlanTier, UUID> traders = tradersOnEachTier();

        assertThatCode(() -> service.requireWithinLimit(traders.get(PlanTier.FREE), Feature.ACTIVE_PLAN_MAX, 2))
                .doesNotThrowAnyException();
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.requireWithinLimit(traders.get(PlanTier.FREE), Feature.ACTIVE_PLAN_MAX, 3));
        for (PlanTier paid : new PlanTier[] {PlanTier.PRO, PlanTier.PREMIUM}) {
            assertThatCode(() -> service.requireWithinLimit(traders.get(paid), Feature.ACTIVE_PLAN_MAX, Long.MAX_VALUE))
                    .as("unlimited on %s", paid)
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ checkDailyQuota (BR-50)

    @Test
    void BR50_withoutTheAiAssistant_aQuestionIsRefusedWithMsg29() {
        storePlans();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(
                        () -> service.checkDailyQuota(tradersOnEachTier().get(PlanTier.FREE), Feature.AI_CHAT_DAILY, 0))
                .satisfies(e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.PLAN_FEATURE_NOT_INCLUDED);
                    assertThat(e.messageArgs()).containsExactly("PRO");
                });
    }

    @ParameterizedTest(name = "{0}: {1} a day")
    @CsvSource({"PRO, 30", "PREMIUM, 100"})
    void BR50_theDailyQuota_refusesTheQuestionAfterTheQuotaWithMsg30(PlanTier tier, int quota) {
        storePlans();
        UUID trader = tradersOnEachTier().get(tier);

        assertThatCode(() -> service.checkDailyQuota(trader, Feature.AI_CHAT_DAILY, quota - 1))
                .doesNotThrowAnyException();
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.checkDailyQuota(trader, Feature.AI_CHAT_DAILY, quota))
                .satisfies(e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.AI_DAILY_QUOTA_EXHAUSTED);
                    assertThat(e.messageArgs()).containsExactly(String.valueOf(quota));
                });
    }

    // ------------------------------------------------------------------ misuse

    @Test
    void UC53_aQuestionAskedOfTheWrongKindOfCode_isRefused() {
        storePlans();
        UUID trader = trader();

        assertThatIllegalArgumentException().isThrownBy(() -> service.getLimit(trader, Feature.VIDEO_POST));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.requireWithinLimit(trader, Feature.FUTURES_ANALYSIS, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.requireWithinLimit(trader, Feature.AI_CHAT_DAILY, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.checkDailyQuota(trader, Feature.WATCHLIST_MAX, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.requireWithinLimit(trader, Feature.WATCHLIST_MAX, -1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.checkDailyQuota(trader, Feature.AI_CHAT_DAILY, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> Feature.VIDEO_POST.limitIn(PlanFixtures.PREMIUM));
    }

    // ------------------------------------------------------------------ messages and statuses

    /** D-61: MSG29 is 403, MSG27 is 409, MSG30 is 429. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = ErrorCode.class,
            names = {"PLAN_FEATURE_NOT_INCLUDED", "PLAN_LIMIT_REACHED", "AI_DAILY_QUOTA_EXHAUSTED"})
    void D61_theRefusals_carryTheirMessageAndStatus(ErrorCode code) {
        Map<ErrorCode, String> messages = Map.of(
                ErrorCode.PLAN_FEATURE_NOT_INCLUDED, "MSG29 403",
                ErrorCode.PLAN_LIMIT_REACHED, "MSG27 409",
                ErrorCode.AI_DAILY_QUOTA_EXHAUSTED, "MSG30 429");

        assertThat(code.messageCode() + " " + code.status().value()).isEqualTo(messages.get(code));
    }

    // ------------------------------------------------------------------ fixtures

    private void assertLimit(PlanTier tier, Feature feature, int max, String items) {
        storePlans();
        UUID trader = tradersOnEachTier().get(tier);

        assertThatCode(() -> service.requireWithinLimit(trader, feature, max - 1))
                .doesNotThrowAnyException();
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.requireWithinLimit(trader, feature, max))
                .satisfies(e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED);
                    assertThat(e.messageArgs()).containsExactly(String.valueOf(max), items, tier.name());
                });
    }

    private void storePlans() {
        packageIds = PlanFixtures.storeAll(packages).stream()
                .collect(Collectors.toMap(SubscriptionPackage::getPackageCode, SubscriptionPackage::getId));
        entityManager.flush();
    }

    /** One Trader on FREE, one with a running PRO subscription, one with a running PREMIUM subscription. */
    private Map<PlanTier, UUID> tradersOnEachTier() {
        UUID free = trader();
        UUID pro = trader();
        UUID premium = trader();
        subscribe(pro, "PRO_YEARLY", "ACTIVE", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364)));
        subscribe(premium, "PREMIUM_YEARLY", "ACTIVE", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364)));
        return Map.of(PlanTier.FREE, free, PlanTier.PRO, pro, PlanTier.PREMIUM, premium);
    }

    private UUID trader() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (user_id, email, password_hash, role, account_status, created_at,
                                                  updated_at)
                        values (?, ?, 'x', 'TRADER', 'ACTIVE', ?, ?)""")
                .params(id, id + "@t104.invalid", Timestamp.from(NOW), Timestamp.from(NOW))
                .update();
        return id;
    }

    /** A PAID order for the package and the subscription it created, with the given status and period. */
    private void subscribe(UUID trader, String packageCode, String status, Instant start, Instant end) {
        UUID order = UUID.randomUUID();
        jdbc.sql("""
                        insert into subscription_order (order_id, user_id, package_id, order_code, amount, currency,
                                                        payment_gateway, order_status, created_at, paid_at, expires_at,
                                                        updated_at)
                        values (?, ?, ?, ?, 1, 'VND', 'VNPAY', 'PAID', ?, ?, ?, ?)""")
                .params(
                        order,
                        trader,
                        packageIds.get(packageCode),
                        "T104-" + order,
                        Timestamp.from(start),
                        Timestamp.from(start),
                        Timestamp.from(start.plusSeconds(900)),
                        Timestamp.from(start))
                .update();
        jdbc.sql("""
                        insert into user_subscription (subscription_id, order_id, start_at, end_at,
                                                       subscription_status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(
                        UUID.randomUUID(),
                        order,
                        Timestamp.from(start),
                        Timestamp.from(end),
                        status,
                        Timestamp.from(start),
                        Timestamp.from(start))
                .update();
    }
}
