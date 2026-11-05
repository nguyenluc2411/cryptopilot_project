package com.cryptopilot.journal.service;

import com.cryptopilot.journal.dto.request.PerformanceFilterRequest;
import com.cryptopilot.journal.dto.response.EquityPointResponse;
import com.cryptopilot.journal.dto.response.PerformanceBreakdownResponse;
import com.cryptopilot.journal.dto.response.PerformanceSummaryResponse;
import java.util.List;
import java.util.UUID;

/**
 * The Trader's performance statistics (SCR-23, UC-25); only the user's own closed trades are read. Every method refuses
 * a period that does not end after it starts, with a
 * {@link com.cryptopilot.common.exception.FieldValidationException} naming {@code from} with MSG15. Every method but
 * {@link #summary} needs {@code ADVANCED_PERFORMANCE} and refuses a plan without it with a
 * {@link com.cryptopilot.common.exception.BusinessException} {@code PLAN_FEATURE_NOT_INCLUDED} (MSG29). Implemented by
 * {@link com.cryptopilot.journal.service.impl.PerformanceServiceImpl}.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-42, BR-62; D-58.
 */
public interface PerformanceService {

    /** The metrics of every plan, with those of the full dashboard when the plan includes them. */
    PerformanceSummaryResponse summary(UUID userId, PerformanceFilterRequest request);

    /** The statistics per pair, the highest total first. */
    List<PerformanceBreakdownResponse> byCoin(UUID userId, PerformanceFilterRequest request);

    /** The statistics per strategy, the highest total first; trades without a strategy form one row. */
    List<PerformanceBreakdownResponse> byStrategy(UUID userId, PerformanceFilterRequest request);

    /** The statistics per market, the highest total first. */
    List<PerformanceBreakdownResponse> byMarket(UUID userId, PerformanceFilterRequest request);

    /** The cumulative net P/L per exit day, oldest first. */
    List<EquityPointResponse> equityCurve(UUID userId, PerformanceFilterRequest request);
}
