package com.cryptopilot.ai.model;

/**
 * The Trader's performance summary in the context (PREMIUM, BR-51). The journal statistics it is read from (T-051)
 * are not built yet, so the snapshot says so instead of leaving the model to guess.
 *
 * @param available whether a summary is included
 * @param message what the model should tell the Trader when it is not
 */
public record PerformanceContext(boolean available, String message) {

    public static final PerformanceContext NOT_YET_AVAILABLE = new PerformanceContext(
            false, "The performance summary is not available yet; say so if the Trader asks about it.");
}
