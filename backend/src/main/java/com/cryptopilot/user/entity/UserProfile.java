package com.cryptopilot.user.entity;

import com.cryptopilot.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The part of an account a person edits: display name, avatar, the figures a new trading plan
 * starts from, and how the account wants to be notified.
 *
 * <p>Part of the {@link UserAccount} aggregate, not an aggregate of its own. Its primary key
 * <em>is</em> the account's key — one row per account, created with the account and deleted with it
 * by {@code fk_user_profile_user ON DELETE CASCADE} — so it has no identity apart from the account
 * it describes. That shared key is why the class holds the account's identifier as its own id
 * rather than an association to {@link UserAccount}: the column is the same column, and mapping it
 * twice would only create a second way to load a row that is already identified.
 *
 * <p>The two default figures are {@link BigDecimal}, never a floating-point type, because they are
 * money and a percentage (ADR-008). They are also nullable and unvalidated here: BR-30 bounds the
 * capital and the risk percentage of a <em>plan</em>, and it is the plan that must refuse a figure
 * outside them. A profile default is a starting point for a form, and a profile that has never
 * said anything is simply empty.
 *
 * <p>Rule: BR-30 (why the ranges are not checked here); ADR-008; TECHNICAL_DESIGN sections 5.4 and
 * 6; SRS UC-06.
 *
 * <p>Reference: Vernon, V. (2013). <i>Implementing Domain-Driven Design</i>. Addison-Wesley, ch. 10
 * (an aggregate is a consistency boundary; a part of one has no identity outside its root).
 */
@Entity
@Table(name = "user_profile")
@AttributeOverride(name = "id", column = @Column(name = "user_id", nullable = false, updatable = false))
public class UserProfile extends BaseEntity {

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "default_capital", precision = 28, scale = 8)
    private BigDecimal defaultCapital;

    @Column(name = "default_risk_percent", precision = 6, scale = 3)
    private BigDecimal defaultRiskPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "trading_style", length = 32)
    private TradingStyle tradingStyle;

    @Column(name = "notify_email", nullable = false)
    private boolean notifyEmail = true;

    @Column(name = "notify_push", nullable = false)
    private boolean notifyPush = true;

    /** For JPA only. */
    protected UserProfile() {}

    private UserProfile(UUID userId, String displayName) {
        super(userId);
        this.displayName = requireText(displayName);
    }

    /**
     * The profile registration creates beside a new account: the display name the person gave, and
     * defaults for everything else.
     *
     * <p>Both notification channels start on, which is the default the column declares. The entity
     * repeats it rather than leaving the column to the database, because an insert written by
     * Hibernate always lists every column, so a database default would never apply and a
     * {@code boolean} left alone would silently store {@code false}.
     *
     * <p>Rule: SRS UC-01 ("a USER_PROFILE with default values").
     */
    public static UserProfile createFor(UUID userId, String displayName) {
        return new UserProfile(userId, displayName);
    }

    /** Renames the profile. The name is shown on posts and comments, so it is never blank. */
    public void rename(String newDisplayName) {
        this.displayName = requireText(newDisplayName);
    }

    /** Points the avatar at a newly uploaded image, or clears it with {@code null}. */
    public void changeAvatar(String newAvatarUrl) {
        this.avatarUrl = newAvatarUrl;
    }

    /**
     * Replaces the figures a new plan starts from. Any of them may be {@code null}, which means the
     * profile expresses no preference and the form starts empty.
     */
    public void updateTradingDefaults(BigDecimal capital, BigDecimal riskPercent, TradingStyle style) {
        this.defaultCapital = capital;
        this.defaultRiskPercent = riskPercent;
        this.tradingStyle = style;
    }

    /** Sets which channels this account accepts notifications on, beside the in-app list. */
    public void updateNotificationPreferences(boolean email, boolean push) {
        this.notifyEmail = email;
        this.notifyPush = push;
    }

    /** The name shown wherever this account appears to others. */
    public String getDisplayName() {
        return displayName;
    }

    /** Where the avatar image is stored, or {@code null} if none was uploaded. */
    public String getAvatarUrl() {
        return avatarUrl;
    }

    /** The capital a new plan starts from, or {@code null} if the profile says nothing. */
    public BigDecimal getDefaultCapital() {
        return defaultCapital;
    }

    /** The risk percentage a new plan starts from, or {@code null} if the profile says nothing. */
    public BigDecimal getDefaultRiskPercent() {
        return defaultRiskPercent;
    }

    /** How long this account usually holds a position, or {@code null} if it has not said. */
    public TradingStyle getTradingStyle() {
        return tradingStyle;
    }

    /** Whether notifications also go out by mail. */
    public boolean isNotifyEmail() {
        return notifyEmail;
    }

    /** Whether notifications also go out as a push message to the registered devices. */
    public boolean isNotifyPush() {
        return notifyPush;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return value;
    }
}
