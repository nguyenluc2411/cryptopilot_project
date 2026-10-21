package com.cryptopilot.admin;

import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * What one module contributes to the administration dashboard and the management reports (SCR-33; UC-40, UC-41). The
 * figures live in the modules that own the tables — users, revenue, forum activity, data ingestion — and those modules
 * depend on this one, so each implements this interface as a bean and the dashboard asks every one of them; the
 * dependency points from them to here, never back.
 *
 * <p>A bucket of a series is the business day, UTC+7, on which its group starts (CR-01; {@link #ZONE}).
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1; CR-01.
 */
public interface AdminMetricSource {

    /** The zone a report's days, weeks and months are cut in (CR-01: business days are UTC+7). */
    String ZONE = "Asia/Ho_Chi_Minh";

    /**
     * Named indicators of the dashboard: counts now, and counts of the period {@code [from, to)}.
     *
     * @return values that are numbers, instants or small maps of them, by a name unique across the sources
     */
    Map<String, Object> indicators(Instant from, Instant to, Instant now);

    /**
     * Named series of the management report over {@code [from, to)}: for each name, the value of each bucket that has
     * one; a bucket without any is left out and reads as 0.
     */
    Map<String, Map<LocalDate, BigDecimal>> series(Instant from, Instant to, ReportGrouping grouping);
}
