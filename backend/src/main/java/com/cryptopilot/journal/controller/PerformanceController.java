package com.cryptopilot.journal.controller;

import com.cryptopilot.common.config.OpenApiConfig;
import com.cryptopilot.journal.dto.request.PerformanceFilterRequest;
import com.cryptopilot.journal.dto.response.EquityPointResponse;
import com.cryptopilot.journal.dto.response.PerformanceBreakdownResponse;
import com.cryptopilot.journal.dto.response.PerformanceSummaryResponse;
import com.cryptopilot.journal.service.PerformanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The performance endpoints of SCR-23 (UC-25), Trader only. The caller is the owner of every trade counted; the user
 * never comes from a parameter. All five take the same filters.
 *
 * <p>Rule: UC-25; SRS 3.6.3; BR-42, BR-62; D-58.
 *
 * <p>Reference: Fowler, M. (2002). <i>Patterns of Enterprise Application Architecture</i>. Addison-Wesley, "Remote
 * Facade" over a "Service Layer".
 */
@Tag(
        name = "Performance",
        description = "The performance dashboard of SCR-23 (UC-25). The period reads exit_time; the source defaults to"
                + " SIMULATED. Trader.")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@RestController
@RequestMapping("/api/v1/journal/performance")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class PerformanceController {

    private static final String BAD_FILTER = "MSG01, MSG15: a filter is invalid";
    private static final String NO_SESSION = "MSG44: no valid session";
    private static final String NOT_IN_PLAN = "MSG29: the plan does not include ADVANCED_PERFORMANCE";

    private final PerformanceService performance;

    @Operation(summary = "The caller's performance metrics; the full set needs ADVANCED_PERFORMANCE (SCR-23)")
    @ApiResponse(
            responseCode = "200",
            description =
                    "Counts, win rate, total net P/L, fees and funding on every plan; `advanced` is null on a plan without"
                            + " ADVANCED_PERFORMANCE. `trades` 0 is the empty result (MSG41)")
    @ApiResponse(responseCode = "400", description = BAD_FILTER)
    @ApiResponse(responseCode = "401", description = NO_SESSION)
    @GetMapping("/summary")
    public PerformanceSummaryResponse summary(
            @AuthenticationPrincipal Jwt caller, @ParameterObject PerformanceFilterRequest filter) {
        return performance.summary(UUID.fromString(caller.getSubject()), filter);
    }

    @Operation(summary = "The caller's statistics per pair, the highest net P/L first (SCR-23)")
    @ApiResponse(
            responseCode = "200",
            description = "One row per pair; `key` is its id, `label` its symbol, null when the pair is not found")
    @ApiResponse(responseCode = "400", description = BAD_FILTER)
    @ApiResponse(responseCode = "401", description = NO_SESSION)
    @ApiResponse(responseCode = "403", description = NOT_IN_PLAN)
    @GetMapping("/by-coin")
    public List<PerformanceBreakdownResponse> byCoin(
            @AuthenticationPrincipal Jwt caller, @ParameterObject PerformanceFilterRequest filter) {
        return performance.byCoin(UUID.fromString(caller.getSubject()), filter);
    }

    @Operation(summary = "The caller's statistics per strategy, the highest net P/L first (SCR-23)")
    @ApiResponse(
            responseCode = "200",
            description = "One row per strategy; `key` is its id, `label` its name. Trades without a strategy form"
                    + " one row with a null `key` and the label Unassigned")
    @ApiResponse(responseCode = "400", description = BAD_FILTER)
    @ApiResponse(responseCode = "401", description = NO_SESSION)
    @ApiResponse(responseCode = "403", description = NOT_IN_PLAN)
    @GetMapping("/by-strategy")
    public List<PerformanceBreakdownResponse> byStrategy(
            @AuthenticationPrincipal Jwt caller, @ParameterObject PerformanceFilterRequest filter) {
        return performance.byStrategy(UUID.fromString(caller.getSubject()), filter);
    }

    @Operation(summary = "The caller's statistics per market, the highest net P/L first (SCR-23)")
    @ApiResponse(responseCode = "200", description = "One row per market; `key` and `label` are SPOT or FUTURES")
    @ApiResponse(responseCode = "400", description = BAD_FILTER)
    @ApiResponse(responseCode = "401", description = NO_SESSION)
    @ApiResponse(responseCode = "403", description = NOT_IN_PLAN)
    @GetMapping("/by-market")
    public List<PerformanceBreakdownResponse> byMarket(
            @AuthenticationPrincipal Jwt caller, @ParameterObject PerformanceFilterRequest filter) {
        return performance.byMarket(UUID.fromString(caller.getSubject()), filter);
    }

    @Operation(summary = "The caller's cumulative net P/L per exit day (SCR-23)")
    @ApiResponse(
            responseCode = "200",
            description = "One point per day with an exit, oldest first; there is no point at `from`, the client"
                    + " adds the 0 baseline")
    @ApiResponse(responseCode = "400", description = BAD_FILTER)
    @ApiResponse(responseCode = "401", description = NO_SESSION)
    @ApiResponse(responseCode = "403", description = NOT_IN_PLAN)
    @GetMapping("/equity-curve")
    public List<EquityPointResponse> equityCurve(
            @AuthenticationPrincipal Jwt caller, @ParameterObject PerformanceFilterRequest filter) {
        return performance.equityCurve(UUID.fromString(caller.getSubject()), filter);
    }
}
