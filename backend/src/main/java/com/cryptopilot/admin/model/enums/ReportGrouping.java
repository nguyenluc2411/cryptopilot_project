package com.cryptopilot.admin.model.enums;

/**
 * How a management report groups its period (SRS 3.11.1: "grouping by day, week or month"). A bucket starts at
 * midnight of the business day, UTC+7 (CR-01); a week starts on Monday.
 *
 * <p>Rule: UC-41; SRS 3.11.1; CR-01.
 */
public enum ReportGrouping {
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    private final String sqlUnit;

    ReportGrouping(String sqlUnit) {
        this.sqlUnit = sqlUnit;
    }

    /** The unit of PostgreSQL's {@code date_trunc}. */
    public String sqlUnit() {
        return sqlUnit;
    }
}
