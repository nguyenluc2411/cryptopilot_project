package com.cryptopilot.journal.dto.request;

import com.cryptopilot.journal.model.enums.PerformanceMarket;
import com.cryptopilot.journal.model.enums.PerformanceSource;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * The filters of SCR-23, the same on every performance endpoint. Every one is optional.
 *
 * <p>Rule: SRS 3.6.3; UC-25; BR-41.
 *
 * @param from the first day counted, or {@code null} from the first trade
 * @param to the first day no longer counted, or {@code null} for tomorrow
 * @param market the market, or {@code null} for both
 * @param source the source, or {@code null} for SIMULATED
 * @param pairId the pair, or {@code null}
 * @param strategyId the strategy, or {@code null}
 */
public record PerformanceFilterRequest(
        @Parameter(description = "First day counted, UTC+7; default the first trade")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,

        @Parameter(description = "First day no longer counted, UTC+7; default tomorrow")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to,

        @Parameter(description = "SPOT, FUTURES or ALL; default ALL")
        PerformanceMarket market,

        @Parameter(description = "SIMULATED, MANUAL or ALL; default SIMULATED")
        PerformanceSource source,

        @Parameter(description = "Only this pair") UUID pairId,
        @Parameter(description = "Only this strategy") UUID strategyId) {}
