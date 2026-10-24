package com.cryptopilot.admin.service;

import com.cryptopilot.admin.dto.response.DashboardResponse;
import com.cryptopilot.admin.dto.response.ReportResponse;
import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.time.LocalDate;

/**
 * The dashboard and the management reports of SCR-33 (UC-40, UC-41), gathered from every
 * {@link com.cryptopilot.admin.AdminMetricSource}. A period is a range of business days, UTC+7 (CR-01), both ends
 * included; without one it is the last 30 days. Implemented by
 * {@link com.cryptopilot.admin.service.impl.DashboardServiceImpl}.
 *
 * <p>Rule: UC-40, UC-41; SRS 3.11.1; CR-01.
 */
public interface DashboardService {

    /**
     * The indicators of the period.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG15 on {@code from} when it is after
     *     {@code to}
     */
    DashboardResponse dashboard(LocalDate from, LocalDate to);

    /**
     * The report of the period, grouped.
     *
     * @throws com.cryptopilot.common.exception.FieldValidationException MSG15 on {@code from} when it is after
     *     {@code to} or the period holds more than 366 rows
     */
    ReportResponse report(LocalDate from, LocalDate to, ReportGrouping groupBy);

    /** The report as CSV: a header of {@code period} and the columns, then one line per row. */
    String csv(ReportResponse report);
}
