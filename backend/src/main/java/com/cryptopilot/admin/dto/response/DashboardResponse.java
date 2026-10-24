package com.cryptopilot.admin.dto.response;

import java.time.LocalDate;
import java.util.Map;

/**
 * The indicators of SCR-33 (UC-40). Counts named "new…", {@code paidOrders}, {@code revenue},
 * {@code revenueByPackage} and {@code aiQuestions} are of the period; the others are now: {@code totalUsers},
 * {@code activeUsers} (signed in during the last 7 days), {@code activeSubscriptions}, {@code openReports},
 * {@code latestCandle} (per market).
 *
 * <p>Rule: UC-40; SRS 3.11.1.
 *
 * @param from the first business day of the period, UTC+7
 * @param to the last business day of the period, included
 * @param indicators the indicators by name
 */
public record DashboardResponse(LocalDate from, LocalDate to, Map<String, Object> indicators) {}
