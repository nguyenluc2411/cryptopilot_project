package com.cryptopilot.trading.entity;

import com.cryptopilot.common.entity.BaseEntity;
import com.cryptopilot.trading.model.PlanWarning;
import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.Getter;

/**
 * One warning a plan holds, stored with the severity its type had when it was raised. It belongs to its plan's
 * aggregate: only {@link TradingPlan} creates it, and replacing the plan's warnings deletes it.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29; SRS 3.5.1 (warnings replaced on each save).
 */
@Getter
@Entity
@Table(name = "trading_plan_warning")
@AttributeOverride(name = "id", column = @Column(name = "warning_id", nullable = false, updatable = false))
public class TradingPlanWarning extends BaseEntity {

    /** The length of {@code warning_message}. */
    static final int MESSAGE_LENGTH = 500;

    @Enumerated(EnumType.STRING)
    @Column(name = "warning_type", nullable = false, updatable = false, length = 32)
    private WarningType warningType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false, length = 32)
    private WarningSeverity severity;

    @Column(name = "warning_message", nullable = false, updatable = false, length = MESSAGE_LENGTH)
    private String warningMessage;

    /** For JPA only. */
    protected TradingPlanWarning() {}

    TradingPlanWarning(PlanWarning warning) {
        Objects.requireNonNull(warning, "warning");
        if (warning.message().length() > MESSAGE_LENGTH) {
            throw new IllegalArgumentException("a warning message is at most " + MESSAGE_LENGTH + " characters");
        }
        this.warningType = warning.type();
        this.severity = warning.severity();
        this.warningMessage = warning.message();
    }

    /** The warning as the rules raised it. */
    PlanWarning toPlanWarning() {
        return new PlanWarning(warningType, warningMessage);
    }
}
