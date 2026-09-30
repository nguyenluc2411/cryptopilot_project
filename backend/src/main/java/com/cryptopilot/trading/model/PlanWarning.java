package com.cryptopilot.trading.model;

import com.cryptopilot.trading.model.enums.WarningSeverity;
import com.cryptopilot.trading.model.enums.WarningType;
import java.util.Objects;

/**
 * One warning raised for a plan, as {@code trading_plan_warning} stores it and MSG17 shows it.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29; SRS 3.5.1.
 *
 * @param type the kind of warning
 * @param message the warning message, with the values that raised it
 */
public record PlanWarning(WarningType type, String message) {

    public PlanWarning {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
    }

    /** The severity the type's rule gives it. */
    public WarningSeverity severity() {
        return type.severity();
    }
}
