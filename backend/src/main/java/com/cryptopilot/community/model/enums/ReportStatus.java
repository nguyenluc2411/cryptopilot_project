package com.cryptopilot.community.model.enums;

/**
 * Where a report stands in the moderation queue (SCR-36): filed, opened by a moderator, decided.
 *
 * <p>Rule: UC-33, UC-43; SRS 3.7.4, 3.11.3.
 */
public enum ReportStatus {
    OPEN,
    REVIEWING,
    RESOLVED
}
