package com.cryptopilot.community.model.enums;

/**
 * Why a Trader reports a post (SCR-27). {@link #OTHER} needs a description.
 *
 * <p>Rule: UC-33; SRS 3.7.4.
 */
public enum ReportReason {
    SPAM,
    SCAM,
    ABUSE,
    MISLEADING,
    OTHER
}
