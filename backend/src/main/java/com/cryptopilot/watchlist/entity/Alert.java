package com.cryptopilot.watchlist.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.market.model.enums.MarketType;
import com.cryptopilot.watchlist.exception.IllegalAlertStateException;
import com.cryptopilot.watchlist.model.AlertDefinition;
import com.cryptopilot.watchlist.model.enums.AlertIndicator;
import com.cryptopilot.watchlist.model.enums.AlertStatus;
import com.cryptopilot.watchlist.model.enums.AlertType;
import com.cryptopilot.watchlist.model.enums.ConditionOperator;
import com.cryptopilot.watchlist.model.enums.TriggerMode;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

/**
 * An alert rule on one of the Trader's watched pairs (BR-16), with its status and the bookkeeping the alert engine
 * keeps between evaluations. The rule itself arrives already checked as an {@link AlertDefinition}; this class guards
 * the status, whose changes go through {@link AlertStatus}'s transition table.
 *
 * <p>The engine fields ({@code triggerCount}, {@code lastTriggeredAt}, {@code lastBarOpenTime},
 * {@code lastEvaluatedValue}) are written by NSF-06 (T-055) only. An edit clears the last bar and the last value,
 * because a cross must be measured against the new rule, not the old one.
 *
 * <p>Rule: BR-16, BR-17, BR-19; SRS 3.4.2, 3.4.3.
 *
 * <p>Reference: Evans, E. (2003). <i>Domain-Driven Design</i>. Addison-Wesley, ch. 6 "Aggregates" (state changes go
 * through the root, which keeps its invariants).
 */
@Getter
@Entity
@Table(name = "alert")
@AttributeOverride(name = "id", column = @Column(name = "alert_id", nullable = false, updatable = false))
public class Alert extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "watchlist_id", nullable = false, updatable = false)
    private UUID watchlistId;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 32)
    private MarketType market;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 32)
    private AlertType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "indicator_name", length = 32)
    private AlertIndicator indicator;

    /** 15m, 1h, 4h or 1d; {@code null} for a PRICE alert. */
    @Column(name = "timeframe", length = 32)
    private String timeframe;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_operator", nullable = false, length = 32)
    private ConditionOperator condition;

    /** {@code null} for MACD_CROSS and EMA_CROSS, which compare two lines (D-76). */
    @Column(name = "threshold_value", precision = 28, scale = 12)
    private BigDecimal threshold;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode", nullable = false, length = 32)
    private TriggerMode triggerMode;

    @Column(name = "cooldown_minutes")
    private Integer cooldownMinutes;

    /** Always true: every alert is shown in the app (NSF-15). */
    @Column(name = "notify_in_app", nullable = false)
    private boolean notifyInApp;

    @Column(name = "notify_email", nullable = false)
    private boolean notifyEmail;

    @Column(name = "notify_push", nullable = false)
    private boolean notifyPush;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_status", nullable = false, length = 32)
    private AlertStatus status;

    @Column(name = "trigger_count", nullable = false)
    private int triggerCount;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** The open time of the candle a ONCE_PER_BAR alert last fired on. */
    @Column(name = "last_bar_open_time")
    private Instant lastBarOpenTime;

    /** The value the condition was last compared with: the "previous value" of a cross (BR-20). */
    @Column(name = "last_evaluated_value", precision = 28, scale = 12)
    private BigDecimal lastEvaluatedValue;

    /** For JPA only. */
    protected Alert() {}

    private Alert(UUID userId, UUID watchlistId, AlertDefinition definition) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.watchlistId = Objects.requireNonNull(watchlistId, "watchlistId must not be null");
        this.notifyInApp = true;
        this.status = AlertStatus.ACTIVE;
        apply(definition);
    }

    /** A new ACTIVE alert on one of the Trader's watchlist rows (SRS 3.4.2). */
    public static Alert create(UUID userId, UUID watchlistId, AlertDefinition definition) {
        return new Alert(userId, watchlistId, definition);
    }

    /**
     * Replaces the rule. A TRIGGERED or EXPIRED alert becomes ACTIVE again; an ACTIVE or PAUSED one keeps its status
     * (SRS 3.4.3).
     */
    public void redefine(AlertDefinition definition) {
        if (status.reactivatesOnEdit()) {
            moveTo(AlertStatus.ACTIVE);
        }
        apply(definition);
        lastBarOpenTime = null;
        lastEvaluatedValue = null;
    }

    /** ACTIVE to PAUSED. */
    public void pause() {
        moveTo(AlertStatus.PAUSED);
    }

    /** PAUSED to ACTIVE. */
    public void resume() {
        if (status != AlertStatus.PAUSED) {
            // EXPIRED → ACTIVE exists in the table, but only through an edit.
            throw new IllegalAlertStateException(getId(), status, AlertStatus.ACTIVE);
        }
        moveTo(AlertStatus.ACTIVE);
    }

    private void moveTo(AlertStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalAlertStateException(getId(), status, target);
        }
        status = target;
    }

    private void apply(AlertDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        market = definition.market();
        type = definition.type();
        indicator = definition.indicator();
        timeframe = definition.timeframe();
        condition = definition.condition();
        threshold = definition.threshold();
        triggerMode = definition.triggerMode();
        cooldownMinutes = definition.cooldownMinutes();
        notifyEmail = definition.notifyEmail();
        notifyPush = definition.notifyPush();
        expiresAt = definition.expiresAt();
    }
}
