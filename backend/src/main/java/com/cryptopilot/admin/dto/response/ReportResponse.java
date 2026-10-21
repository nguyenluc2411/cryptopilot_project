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
     * One day, week or month.
     *
     * @param period the first business day of the group
     * @param values the value of each column
     */
    public record Row(LocalDate period, Map<String, BigDecimal> values) {}
}
