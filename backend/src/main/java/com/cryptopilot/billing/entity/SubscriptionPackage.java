package com.cryptopilot.billing.entity;

import com.cryptopilot.billing.PlanEntitlements;
import com.cryptopilot.billing.PlanTier;
import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.Getter;

/**
 * A package of a plan tier: its price and duration, and the entitlements of the tier it belongs to.
 *
 * <p>FREE is the plan of a Trader without an ACTIVE subscription, so the FREE package is priced 0, has no duration,
 * cannot be bought and is never deactivated; a paid package is priced above 0 and lasts 1–366 days. Both factories
 * enforce this before the row reaches the database, which enforces it again ({@code ck_subscription_package_free},
 * {@code ck_subscription_package_free_active}, {@code ck_subscription_package_paid_price},
 * {@code ck_subscription_package_paid_duration}) together with the single FREE package
 * ({@code uq_subscription_package_one_free}). There is no way to deactivate a package yet. {@code tier_rank} is written from the tier and never set on its own.
 *
 * <p>Rule: BR-62, BR-56, BR-63; SRS v1.1 entity 34, SRS 3.11.5; D-58, D-59.
 * <p>Reference: Codd, E. F. (1970). <i>A Relational Model of Data for Large Shared Data Banks</i>. Communications of
 * the ACM 13(6) (the data kept consistent by the relations that hold it).
 * <p>Reference: Date, C. J. (2003). <i>An Introduction to Database Systems</i> (8th ed.). Addison-Wesley, ch. 9
 * (integrity constraints declared on the relation rather than in each program).
 */
@Getter
@Entity
@Table(name = "subscription_package")
@AttributeOverride(name = "id", column = @Column(name = "package_id", nullable = false, updatable = false))
public class SubscriptionPackage extends BaseEntity {

    /** The only currency a package is priced in. */
    static final String CURRENCY = "VND";

    /** The shortest and longest duration of a paid package, in days (SRS 3.11.5). */
    static final int MIN_DURATION_DAYS = 1;

    static final int MAX_DURATION_DAYS = 366;

    /** Longest package code the column holds ({@code varchar(32)}). */
    static final int CODE_LENGTH = 32;

    /** Longest package name the column holds ({@code varchar(100)}). */
    static final int NAME_LENGTH = 100;

    @Column(name = "package_code", nullable = false, length = CODE_LENGTH, updatable = false)
    private String packageCode;

    @Column(name = "package_name", nullable = false, length = NAME_LENGTH)
    private String packageName;

    /** The price in VND; later changes never reach existing orders (BR-56). */
    @Column(name = "price_amount", nullable = false, precision = 18, scale = 0)
    private BigDecimal priceAmount;

    @Column(name = "currency", nullable = false, length = 32)
    private String currency;

    /** The duration of a paid package in days, or {@code null} for FREE. */
    @Column(name = "duration_days")
    private Integer durationDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 32, updatable = false)
    private PlanTier tier;

    @Column(name = "tier_rank", nullable = false, updatable = false)
    private short tierRank;

    @Column(name = "is_purchasable", nullable = false)
    private boolean purchasable;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "futures_analysis", nullable = false)
    private boolean futuresAnalysis;

    @Column(name = "score_components", nullable = false)
    private boolean scoreComponents;

    /** {@code null} is unlimited. */
    @Column(name = "active_plan_max")
    private Integer activePlanMax;

    /** {@code null} is unlimited. */
    @Column(name = "watchlist_max")
    private Integer watchlistMax;

    /** {@code null} is unlimited. */
    @Column(name = "active_alert_max")
    private Integer activeAlertMax;

    @Column(name = "indicator_alert", nullable = false)
    private boolean indicatorAlert;

    @Column(name = "external_alert_channels", nullable = false)
    private boolean externalAlertChannels;

    @Column(name = "advanced_performance", nullable = false)
    private boolean advancedPerformance;

    @Column(name = "news_ai_insight", nullable = false)
    private boolean newsAiInsight;

    /** {@code AI_CHAT_DAILY}: AI Assistant questions per day. */
    @Column(name = "ai_daily_quota", nullable = false)
    private int aiDailyQuota;

    @Column(name = "ai_performance_context", nullable = false)
    private boolean aiPerformanceContext;

    /** {@code VIDEO_POST}. */
    @Column(name = "can_post_video", nullable = false)
    private boolean canPostVideo;

    /** For JPA only. */
    protected SubscriptionPackage() {}

    private SubscriptionPackage(
            String code,
            String name,
            PlanTier tier,
            BigDecimal price,
            Integer durationDays,
            boolean purchasable,
            PlanEntitlements entitlements) {
        this.packageCode = requireText(code, "code", CODE_LENGTH);
        this.packageName = requireText(name, "name", NAME_LENGTH);
        this.tier = tier;
        this.tierRank = (short) tier.rank();
        this.priceAmount = price;
        this.currency = CURRENCY;
        this.durationDays = durationDays;
        this.purchasable = purchasable;
        this.active = true;
        apply(Objects.requireNonNull(entitlements, "entitlements must not be null"));
    }

    /** The single FREE package: price 0, no duration, not purchasable (BR-62). */
    public static SubscriptionPackage free(String code, String name, PlanEntitlements entitlements) {
        return new SubscriptionPackage(code, name, PlanTier.FREE, BigDecimal.ZERO, null, false, entitlements);
    }

    /**
     * A package of a paid tier, on sale. The price is above 0: the gateway charges a real amount and the upgrade
     * credit is computed from it (SRS 3.11.5, BR-63).
     *
     * @param tier PRO or PREMIUM
     * @param price the price in VND, above 0
     * @param durationDays 1–366
     */
    public static SubscriptionPackage paid(
            String code,
            String name,
            PlanTier tier,
            BigDecimal price,
            int durationDays,
            PlanEntitlements entitlements) {
        if (Objects.requireNonNull(tier, "tier must not be null") == PlanTier.FREE) {
            throw new IllegalArgumentException("the FREE package is created with free(), not sold");
        }
        if (Objects.requireNonNull(price, "price must not be null").signum() <= 0) {
            throw new IllegalArgumentException("a paid package is priced above 0 VND, was " + price);
        }
        if (durationDays < MIN_DURATION_DAYS || durationDays > MAX_DURATION_DAYS) {
            throw new IllegalArgumentException("a paid package lasts " + MIN_DURATION_DAYS + "–" + MAX_DURATION_DAYS
                    + " days, was " + durationDays);
        }
        return new SubscriptionPackage(code, name, tier, price, durationDays, true, entitlements);
    }

    /** The entitlements of the package's tier. */
    public PlanEntitlements entitlements() {
        return new PlanEntitlements(
                futuresAnalysis,
                scoreComponents,
                activePlanMax,
                watchlistMax,
                activeAlertMax,
                indicatorAlert,
                externalAlertChannels,
                advancedPerformance,
                newsAiInsight,
                aiDailyQuota,
                aiPerformanceContext,
                canPostVideo);
    }

    private void apply(PlanEntitlements e) {
        this.futuresAnalysis = e.futuresAnalysis();
        this.scoreComponents = e.scoreComponents();
        this.activePlanMax = e.activePlanMax();
        this.watchlistMax = e.watchlistMax();
        this.activeAlertMax = e.activeAlertMax();
        this.indicatorAlert = e.indicatorAlert();
        this.externalAlertChannels = e.externalAlertChannels();
        this.advancedPerformance = e.advancedPerformance();
        this.newsAiInsight = e.newsAiInsight();
        this.aiDailyQuota = e.aiChatDaily();
        this.aiPerformanceContext = e.aiPerformanceContext();
        this.canPostVideo = e.videoPost();
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be 1–" + maxLength + " characters, was " + value);
        }
        return value;
    }
}
