package com.cryptopilot.trading.model.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a trading plan. The transition table below is the only place that says which status may follow
 * which; the plan changes status through it and nowhere else. The names are the values {@code trading_plan.plan_status}
 * accepts.
 *
 * <p>Rule: BR-32.
 *
 * <p>Reference: Harel, D. (1987). Statecharts: A visual formalism for complex systems. <i>Science of Computer
 * Programming</i>, 8(3), 231–274 (states with an explicit, finite set of transitions).
 */
public enum PlanStatus {

    /** Saved and still editable; may be activated or cancelled. */
    DRAFT,

    /** Waiting for its entry; may be filled, cancelled or expire. */
    ACTIVE,

    /** The entry was filled; the position lives on in the journal. Terminal for the plan. */
    EXECUTED,

    /** Cancelled by the Trader. Terminal. */
    CANCELLED,

    /** The expiry passed before the entry was filled. Terminal. */
    EXPIRED;

    private static final Map<PlanStatus, Set<PlanStatus>> NEXT = transitions();

    /** Whether a plan in this status may move to {@code target}. */
    public boolean canTransitionTo(PlanStatus target) {
        return NEXT.get(this).contains(target);
    }

    /** Whether no status may follow this one. */
    public boolean isTerminal() {
        return NEXT.get(this).isEmpty();
    }

    private static Map<PlanStatus, Set<PlanStatus>> transitions() {
        Map<PlanStatus, Set<PlanStatus>> next = new EnumMap<>(PlanStatus.class);
        next.put(DRAFT, EnumSet.of(ACTIVE, CANCELLED));
        next.put(ACTIVE, EnumSet.of(EXECUTED, CANCELLED, EXPIRED));
        next.put(EXECUTED, EnumSet.noneOf(PlanStatus.class));
        next.put(CANCELLED, EnumSet.noneOf(PlanStatus.class));
        next.put(EXPIRED, EnumSet.noneOf(PlanStatus.class));
        next.replaceAll((status, targets) -> Collections.unmodifiableSet(targets));
        return Collections.unmodifiableMap(next);
    }
}
