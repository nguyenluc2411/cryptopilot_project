package com.cryptopilot.watchlist.model.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of an alert. The transition table below is the only place that says which status may follow which.
 * The Trader pauses, resumes and edits (SRS 3.4.3); TRIGGERED and EXPIRED are set only by the alert engine (NSF-06),
 * and an edit brings a TRIGGERED or EXPIRED alert back to ACTIVE. The names are the values
 * {@code alert.alert_status} accepts.
 *
 * <p>Rule: BR-17, BR-19; SRS 3.4.3, 3.4.4.
 *
 * <p>Reference: Harel, D. (1987). Statecharts: A visual formalism for complex systems. <i>Science of Computer
 * Programming</i>, 8(3), 231–274 (states with an explicit, finite set of transitions).
 */
public enum AlertStatus {

    /** Evaluated by the engine; counts against {@code ACTIVE_ALERT_MAX}. */
    ACTIVE,

    /** Kept but not evaluated, until the Trader resumes it. */
    PAUSED,

    /** A ONCE alert that fired. Re-activated by editing it. */
    TRIGGERED,

    /** Its expiry passed. Re-activated by editing it. */
    EXPIRED;

    private static final Map<AlertStatus, Set<AlertStatus>> NEXT = transitions();

    /** Whether an alert in this status may move to {@code target}. */
    public boolean canTransitionTo(AlertStatus target) {
        return NEXT.get(this).contains(target);
    }

    /** Whether an edit of an alert in this status makes it ACTIVE again (SRS 3.4.3). */
    public boolean reactivatesOnEdit() {
        return this == TRIGGERED || this == EXPIRED;
    }

    private static Map<AlertStatus, Set<AlertStatus>> transitions() {
        Map<AlertStatus, Set<AlertStatus>> next = new EnumMap<>(AlertStatus.class);
        next.put(ACTIVE, EnumSet.of(PAUSED, TRIGGERED, EXPIRED));
        next.put(PAUSED, EnumSet.of(ACTIVE, EXPIRED));
        next.put(TRIGGERED, EnumSet.of(ACTIVE));
        next.put(EXPIRED, EnumSet.of(ACTIVE));
        next.replaceAll((status, targets) -> Collections.unmodifiableSet(targets));
        return Collections.unmodifiableMap(next);
    }
}
