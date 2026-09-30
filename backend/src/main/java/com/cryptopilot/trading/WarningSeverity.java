package com.cryptopilot.trading;

/**
 * How serious a plan warning is, lowest first. Only {@link #BLOCKING} stops a plan from being activated; a plan with
 * any warning can still be saved as a draft.
 *
 * <p>Rule: BR-25, BR-26, BR-28, BR-29; SRS 3.5.1 (MSG17, MSG18).
 */
public enum WarningSeverity {

    /** Shown for information only. */
    INFO,

    /** Shown to the Trader, who may still activate the plan. */
    WARNING,

    /** The plan cannot be activated while it holds this warning. */
    BLOCKING
}
