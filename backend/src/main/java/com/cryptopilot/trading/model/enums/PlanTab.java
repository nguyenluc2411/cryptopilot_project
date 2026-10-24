package com.cryptopilot.trading.model.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * The tabs of the plan list (SCR-16): each shows the plans in its statuses; cancelled, expired and invalidated plans
 * share one.
 *
 * <p>Rule: SRS 3.5.2; BR-32, BR-68; D-112.
 */
public enum PlanTab {
    DRAFT(EnumSet.of(PlanStatus.DRAFT)),
    ACTIVE(EnumSet.of(PlanStatus.ACTIVE)),
    EXECUTED(EnumSet.of(PlanStatus.EXECUTED)),
    CANCELLED_EXPIRED(EnumSet.of(PlanStatus.CANCELLED, PlanStatus.EXPIRED, PlanStatus.INVALIDATED));

    private final Set<PlanStatus> statuses;

    PlanTab(Set<PlanStatus> statuses) {
        this.statuses = statuses;
    }

    /** The statuses the tab shows. */
    public Set<PlanStatus> statuses() {
        return EnumSet.copyOf(statuses);
    }
}
