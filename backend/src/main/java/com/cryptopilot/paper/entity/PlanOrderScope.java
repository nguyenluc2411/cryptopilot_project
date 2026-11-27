package com.cryptopilot.paper.entity;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The plan the orders placed on this thread carry out (RF-33). Applying a plan places its orders through the same
 * paths as any other paper order; every order those paths store while the scope is open names the plan, its take
 * profit and stop loss legs included, so a fill of any of them is known to belong to the plan.
 *
 * <p>Rule: D-117; RF-33.
 */
public final class PlanOrderScope {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private PlanOrderScope() {}

    /** Runs {@code work} with every order it stores naming {@code planId}. */
    public static <T> T within(UUID planId, Supplier<T> work) {
        UUID before = CURRENT.get();
        CURRENT.set(planId);
        try {
            return work.get();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }

    /** The plan of the open scope, or {@code null}. */
    static UUID current() {
        return CURRENT.get();
    }
}
