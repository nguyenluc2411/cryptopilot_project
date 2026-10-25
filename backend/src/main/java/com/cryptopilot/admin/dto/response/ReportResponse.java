package com.cryptopilot.admin.dto.response;

import com.cryptopilot.admin.model.enums.ReportGrouping;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A management report (UC-41): one row per day, week or month of the period, every row with every column, 0 where
 * nothing happened. The columns are {@code newUsers}, {@code paidOrders}, {@code revenue} (VND), one
 * {@code revenue:<package code>} per package sold in the period, {@code newPosts}, {@code newComments} and
 * {@code aiQuestions}.
 *
 * <p>Rule: UC-41; SRS 3.11.1.
 *
 * @param from the first business day of the period, UTC+7
 * @param to the last business day, included
 * @param groupBy DAY, WEEK or MONTH
 * @param columns the column names, in the order of the CSV export
 * @param rows the rows, oldest first
 */
public record ReportResponse(
        LocalDate from, LocalDate to, ReportGrouping groupBy, List<String> columns, List<Row> rows) {

    /**
     * One day, week or month. The first and the last group of a WEEK or MONTH report may be cut by the period: a week
     * labelled with its Monday counts only the days from {@code from} on, and a month only up to {@code to}.
     * {@code coveredFrom} and {@code coveredTo} say which days a row really counts, so a short group is not read as a
     * full one.
     *
     * @param period the first business day of the group, its label
     * @param coveredFrom the first day the row counts, the later of {@code period} and the report's {@code from}
     * @param coveredTo the last day the row counts, included: the earlier of the group's last day and {@code to}
     * @param values the value of each column
     */
    public record Row(LocalDate period, LocalDate coveredFrom, LocalDate coveredTo, Map<String, BigDecimal> values) {}
}
